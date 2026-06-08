package dev.ktrics.client

import dev.ktrics.client.proto.DaemonEndpoint
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel

/**
 * Thrown when the daemon's runtime prerequisite — a usable system Java [DaemonLauncher.MIN_JAVA_MAJOR]+
 * — can't be satisfied, so spawning would only fail silently. Carries a user-facing, actionable message.
 */
class DaemonStartException(
    message: String,
) : Exception(message)

/**
 * Auto-spawns `ktricsd` and waits for its socket (daemon auto-spawns on first client call,
 * self-terminates on idle timeout). The native client cannot run analysis itself — it links none
 * of the platform — so every analysis path goes through a spawned daemon, which runs on the caller's
 * system Java (no JRE is bundled): a missing or too-old runtime is caught up front, not as a timeout.
 */
class DaemonLauncher(
    private val projectRoot: File,
    /**
     * The actual process launch, injected so tests can drive the spawn/respawn paths without forking a
     * real `ktricsd` (DI seam). Production uses [ProcessBuilder.start]; the command and environment are
     * fully assembled before this is called.
     */
    private val startProcess: (ProcessBuilder) -> Unit = { it.start() },
    /**
     * Identifies a pid-file process as a ktrics daemon before it is signalled (DI seam: test victims
     * are `sleep`/`sh`, which the production check would — correctly — refuse to kill).
     */
    private val isDaemonProcess: (ProcessHandle) -> Boolean = ::looksLikeDaemon,
    /**
     * Environment the daemon runtime is resolved from (JAVA_HOME / KTRICS_DAEMON). Injected so the Java
     * preflight runs deterministically in tests; production reads the real process environment.
     */
    private val env: Map<String, String> = System.getenv(),
    /**
     * Probes a resolved `java` executable for its `-version` banner (null when it can't be run). DI
     * seam: tests substitute a fixed banner instead of forking a real JVM.
     */
    private val javaVersionProbe: (String) -> String? = ::probeJavaVersion,
) {
    /**
     * Ensures a daemon is listening; spawns one and waits up to [timeoutMs] for the socket. When a cold
     * spawn is needed, the system Java is verified first ([requireUsableJava]) so an absent or too-old
     * runtime fails immediately with a clear error rather than after the full socket-wait timeout.
     */
    fun ensureRunning(timeoutMs: Long = DEFAULT_SPAWN_TIMEOUT_MS): Boolean {
        val socket = DaemonEndpoint.socketPath(projectRoot)
        if (isConnectable(socket)) return true
        requireUsableJava()
        spawn()
        return waitForSocket(socket, timeoutMs)
    }

    /** Forcibly (re)spawns the daemon — used after a protocol version mismatch. */
    fun respawn(timeoutMs: Long = DEFAULT_SPAWN_TIMEOUT_MS): Boolean {
        stopExisting()
        spawn()
        return waitForSocket(DaemonEndpoint.socketPath(projectRoot), timeoutMs)
    }

    private fun spawn() {
        val command = daemonCommand() + listOf("--serve", "--root", projectRoot.absolutePath)
        val builder =
            ProcessBuilder(command)
                .directory(projectRoot)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment()["KTRICS_DAEMON_DETACHED"] = "1" }
        startProcess(builder)
    }

    /**
     * Stops the existing daemon and waits (bounded) for it to actually exit before returning, so a
     * subsequent [spawn] + [waitForSocket] can't connect to the still-alive old process and re-trigger
     * the same version mismatch. SIGTERM first, then SIGKILL if it outlives the grace window.
     */
    private fun stopExisting() {
        val pidFile = DaemonEndpoint.pidFile(projectRoot)
        val pid = pidFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: return
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        // A pid file can outlive its daemon (kill -9 skips the shutdown hook) and the OS can recycle
        // the pid — never signal a process that isn't recognizably a ktrics daemon.
        if (!isDaemonProcess(handle)) return
        handle.destroy()
        if (!awaitExit(handle, STOP_GRACE_MS)) {
            handle.destroyForcibly()
            awaitExit(handle, STOP_FORCE_MS)
        }
    }

    /** Waits up to [timeoutMs] for [handle] to terminate; returns true if it exited within the window. */
    private fun awaitExit(
        handle: ProcessHandle,
        timeoutMs: Long,
    ): Boolean =
        try {
            handle.onExit().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        } catch (_: java.util.concurrent.TimeoutException) {
            !handle.isAlive
        } catch (_: Exception) {
            // Interrupted or the handle no longer tracks a live process: treat as exited.
            !handle.isAlive
        }

    /** Locates the `ktricsd` launcher: explicit env wins, else a sibling of the client binary, else PATH. */
    private fun daemonCommand(): List<String> {
        env["KTRICS_DAEMON"]?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        val selfDir = ProcessHandle.current().info().command().map { File(it).parentFile }.orElse(null)
        val sibling = selfDir?.let { File(it, daemonBinaryName()) }
        if (sibling != null && sibling.canExecute()) return listOf(sibling.absolutePath)
        return listOf(daemonBinaryName())
    }

    private fun daemonBinaryName(): String = if (isWindows()) "ktricsd.bat" else "ktricsd"

    /**
     * Fails fast before a doomed spawn (which would otherwise just time out waiting for a socket that
     * never binds) when the daemon's runtime is absent or too old. The daemon is Java 21 bytecode and
     * runs on the *caller's* JVM, so a usable Java [MIN_JAVA_MAJOR]+ must be resolvable. Skipped when
     * KTRICS_DAEMON overrides the launch command — the user owns the runtime in that case.
     */
    private fun requireUsableJava() {
        if (!env["KTRICS_DAEMON"].isNullOrBlank()) return
        val javaExe = resolveJavaExecutable()
        val banner = javaExe?.let { javaVersionProbe(it) }
        if (javaExe == null || banner == null) {
            throw DaemonStartException(
                "ktrics: no Java runtime found to start the analysis daemon (ktricsd). ktrics needs a " +
                    "JDK $MIN_JAVA_MAJOR or newer — install one and set JAVA_HOME, or put `java` on your PATH.",
            )
        }
        val major = parseJavaMajor(banner)
        if (major == null || major < MIN_JAVA_MAJOR) {
            val found = major?.let { "Java $it" } ?: "an unrecognized Java version"
            throw DaemonStartException(
                "ktrics: the analysis daemon (ktricsd) needs Java $MIN_JAVA_MAJOR or newer, but `$javaExe` " +
                    "reports $found. Point JAVA_HOME at a JDK $MIN_JAVA_MAJOR+.",
            )
        }
    }

    /**
     * The `java` the generated start script will use: `$JAVA_HOME/bin/java` when JAVA_HOME is set (null
     * when that path isn't executable — the script hard-fails there too, never falling back to PATH),
     * else the bare `java`, left for the OS to resolve against PATH at probe time.
     */
    private fun resolveJavaExecutable(): String? {
        val exe = if (isWindows()) "java.exe" else "java"
        val javaHome = env["JAVA_HOME"]?.takeIf { it.isNotBlank() } ?: return exe
        val candidate = File(File(javaHome, "bin"), exe)
        return candidate.absolutePath.takeIf { candidate.canExecute() }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private fun isConnectable(socket: File): Boolean {
        if (!socket.exists()) return false
        return try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use {
                it.connect(UnixDomainSocketAddress.of(socket.toPath()))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun waitForSocket(
        socket: File,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (isConnectable(socket)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    companion object {
        const val DEFAULT_SPAWN_TIMEOUT_MS: Long = 30_000
        private const val POLL_INTERVAL_MS: Long = 25

        /** Grace window for the old daemon to exit on SIGTERM before we escalate to SIGKILL. */
        private const val STOP_GRACE_MS: Long = 1_000

        /** Bounded wait after SIGKILL; never hangs respawn even if the process is unkillable. */
        private const val STOP_FORCE_MS: Long = 500

        /** Minimum system Java the daemon runs on — it is Java 21 bytecode and embeds the IntelliJ platform. */
        const val MIN_JAVA_MAJOR: Int = 21

        /** Bounded wait for `java -version`; a hung probe must never stall daemon startup. */
        private const val JAVA_PROBE_TIMEOUT_MS: Long = 5_000

        /** Runs `java -version` (the banner goes to stderr) and returns its output, or null if it can't run. */
        private fun probeJavaVersion(javaExe: String): String? =
            try {
                val proc = ProcessBuilder(javaExe, "-version").redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                if (proc.waitFor(JAVA_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) && proc.exitValue() == 0) {
                    output
                } else {
                    proc.destroyForcibly()
                    null
                }
            } catch (_: Exception) {
                null
            }

        /**
         * Major version from a `java -version` banner: `"21.0.2"` → 21, legacy `"1.8.0_x"` → 8. Null when
         * the banner carries no `version "..."` token or its leading component isn't numeric.
         */
        internal fun parseJavaMajor(banner: String): Int? {
            val raw = Regex("version \"([^\"]+)\"").find(banner)?.groupValues?.get(1) ?: return null
            val parts = raw.split('.', '_', '-', '+')
            val first = parts.getOrNull(0)?.toIntOrNull() ?: return null
            return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
        }

        /**
         * True when [handle]'s command line is recognizably a ktrics daemon (the `ktricsd` launcher
         * or a JVM running it). When the OS exposes NO command info at all, err toward true — the
         * pid file is our own artifact and the common case is a live daemon we just talked to.
         */
        internal fun looksLikeDaemon(handle: ProcessHandle): Boolean {
            val info = handle.info()
            val command = info.command().orElse(null)
            val line = info.commandLine().orElse(command) ?: return true
            return line.contains("ktricsd", ignoreCase = true) || line.contains("ktrics.daemon", ignoreCase = true)
        }
    }
}
