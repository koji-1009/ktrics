package dev.ktrics.client

import dev.ktrics.client.proto.DaemonEndpoint
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel

/**
 * Auto-spawns `ktricsd` and waits for its socket (daemon auto-spawns on first client call,
 * self-terminates on idle timeout). The native client cannot run analysis itself — it links none
 * of the platform — so every analysis path goes through a spawned daemon.
 */
class DaemonLauncher(
    private val projectRoot: File,
    /**
     * The actual process launch, injected so tests can drive the spawn/respawn paths without forking a
     * real `ktricsd` (DI seam). Production uses [ProcessBuilder.start]; the command and environment are
     * fully assembled before this is called.
     */
    private val startProcess: (ProcessBuilder) -> Unit = { it.start() },
) {
    /** Ensures a daemon is listening; spawns one and waits up to [timeoutMs] for the socket. */
    fun ensureRunning(timeoutMs: Long = DEFAULT_SPAWN_TIMEOUT_MS): Boolean {
        val socket = DaemonEndpoint.socketPath(projectRoot)
        if (isConnectable(socket)) return true
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
        System.getenv("KTRICS_DAEMON")?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        val selfDir = ProcessHandle.current().info().command().map { File(it).parentFile }.orElse(null)
        val sibling = selfDir?.let { File(it, daemonBinaryName()) }
        if (sibling != null && sibling.canExecute()) return listOf(sibling.absolutePath)
        return listOf(daemonBinaryName())
    }

    private fun daemonBinaryName(): String = if (System.getProperty("os.name").startsWith("Windows")) "ktricsd.bat" else "ktricsd"

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
    }
}
