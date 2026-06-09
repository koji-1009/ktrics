package dev.ktrics.client

import dev.ktrics.client.proto.DaemonEndpoint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

/** Daemon auto-spawn orchestration, driven through the injected process-spawn seam. */
class DaemonLauncherTest {
    // A probe banner that satisfies the system-Java preflight, so spawn-path tests exercise the spawn
    // rather than the runtime check (which has its own focused tests below).
    private val okJavaProbe: (String) -> String? = { "openjdk version \"21.0.2\" 2024-01-16" }

    /**
     * Runs [body] with a fresh temp project root, cleaning up the runtime socket/pid afterwards. The
     * real runtime dir (~/.ktrics) is used deliberately: a unix-domain socket path has a ~108-char OS
     * limit, which a deep temp dir would blow past — the per-root hash keeps the file name unique.
     */
    private fun withProject(body: (root: File) -> Unit) {
        val root = createTempDirectory("proj").toFile()
        try {
            body(root)
        } finally {
            runCatching { DaemonEndpoint.socketPath(root).delete() }
            runCatching { DaemonEndpoint.pidFile(root).delete() }
            root.deleteRecursively()
        }
    }

    @Test
    fun `ensureRunning returns true without spawning when the socket is already up`() {
        withProject { root ->
            val spawns = AtomicInteger(0)
            val server = TestUnixServer.start(DaemonEndpoint.socketPath(root))
            try {
                val launcher = DaemonLauncher(root, startProcess = { spawns.incrementAndGet() })
                launcher.ensureRunning(timeoutMs = 1_000) shouldBe true
                spawns.get() shouldBe 0 // already connectable → no spawn
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun `ensureRunning spawns and then sees the socket come up`() {
        withProject { root ->
            var server: AutoCloseable? = null
            try {
                // The injected spawn "starts" the daemon by binding the expected socket.
                val launcher =
                    DaemonLauncher(
                        root,
                        startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) },
                        javaVersionProbe = okJavaProbe,
                    )
                launcher.ensureRunning(timeoutMs = 2_000) shouldBe true
            } finally {
                server?.close()
            }
        }
    }

    @Test
    fun `ensureRunning treats a stale non-socket file at the socket path as not connectable`() {
        withProject { root ->
            // A leftover regular file exists at the socket path but is not a listening socket: connecting
            // throws, exercising isConnectable's catch (→ false), so the launcher falls through to spawning.
            DaemonEndpoint.socketPath(root).writeText("stale")
            val launcher = DaemonLauncher(root, startProcess = { /* never binds a socket */ }, javaVersionProbe = okJavaProbe)
            launcher.ensureRunning(timeoutMs = 150) shouldBe false
        }
    }

    @Test
    fun `ensureRunning times out to false when the spawn never produces a socket`() {
        withProject { root ->
            val launcher = DaemonLauncher(root, startProcess = { /* no socket ever appears */ }, javaVersionProbe = okJavaProbe)
            launcher.ensureRunning(timeoutMs = 150) shouldBe false
        }
    }

    @Test
    fun `spawn assembles --serve, --root, the detach env var, and discards output`() {
        withProject { root ->
            var captured: ProcessBuilder? = null
            val launcher = DaemonLauncher(root, startProcess = { captured = it }, javaVersionProbe = okJavaProbe)
            launcher.ensureRunning(timeoutMs = 50) // spawns (capturing the builder), then times out — no socket comes up
            val builder = captured!!
            builder.command() shouldContain "--serve"
            // --root is followed by the project root path so the daemon analyses the right tree.
            builder.command()[builder.command().indexOf("--root") + 1] shouldBe root.absolutePath
            builder.environment()["KTRICS_DAEMON_DETACHED"] shouldBe "1"
            builder.redirectOutput() shouldBe ProcessBuilder.Redirect.DISCARD
            builder.redirectError() shouldBe ProcessBuilder.Redirect.DISCARD
        }
    }

    @Test
    fun `ensureRunning fails fast with an actionable error when no Java runtime can be run`() =
        // No JAVA_HOME (→ bare `java`) + a probe that reports it unrunnable: no JVM to run on, so the
        // spawn is refused up front rather than left to time out on a socket that never binds.
        assertPreflightRejects({ null }, "JDK 21")

    @Test
    fun `ensureRunning fails fast when the system Java is older than 21`() =
        assertPreflightRejects({ "openjdk version \"17.0.10\" 2024-01-16" }, "Java 21", "17")

    /** The cold-spawn Java preflight must reject [probe]'s runtime with a message naming all [fragments], and never spawn. */
    private fun assertPreflightRejects(
        probe: (String) -> String?,
        vararg fragments: String,
    ) = withProject { root ->
        val spawns = AtomicInteger(0)
        val launcher =
            DaemonLauncher(
                root,
                startProcess = { spawns.incrementAndGet() },
                env = emptyMap(),
                javaVersionProbe = probe,
            )
        val ex = shouldThrow<DaemonStartException> { launcher.ensureRunning(timeoutMs = 1_000) }
        fragments.forEach { ex.message!!.contains(it) shouldBe true }
        spawns.get() shouldBe 0
    }

    @Test
    fun `ensureRunning skips the Java preflight when KTRICS_DAEMON overrides the launch command`() {
        withProject { root ->
            var server: AutoCloseable? = null
            try {
                // KTRICS_DAEMON hands the launch to the user's own command, so the system-Java check must
                // not run — proven by a probe that would fail the check yet a spawn that still happens.
                val launcher =
                    DaemonLauncher(
                        root,
                        startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) },
                        env = mapOf("KTRICS_DAEMON" to "ktricsd"),
                        javaVersionProbe = { null },
                    )
                launcher.ensureRunning(timeoutMs = 2_000) shouldBe true
            } finally {
                server?.close()
            }
        }
    }

    @Test
    fun `ensureRunning runs the real java probe and proceeds on the current JDK`() {
        withProject { root ->
            var server: AutoCloseable? = null
            try {
                // No probe injected → the real `java -version` probe runs against this JVM's JAVA_HOME
                // (the test toolchain is JDK 21+), so the preflight passes and the spawn proceeds.
                val launcher =
                    DaemonLauncher(
                        root,
                        startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) },
                        env = mapOf("JAVA_HOME" to System.getProperty("java.home")),
                    )
                launcher.ensureRunning(timeoutMs = 2_000) shouldBe true
            } finally {
                server?.close()
            }
        }
    }

    @Test
    fun `the real java probe treats a non-zero java -version exit as no runtime`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the fake java launcher is a POSIX script")
        withProject { root ->
            val fakeHome = createTempDirectory("fakejdk").toFile()
            try {
                // A JAVA_HOME whose bin/java runs but exits non-zero: the probe must reject it (exitValue
                // != 0 arm), so the preflight reports no usable runtime.
                val bin = File(fakeHome, "bin").apply { mkdirs() }
                File(bin, "java").apply {
                    writeText("#!/bin/sh\nexit 3\n")
                    setExecutable(true)
                }
                val launcher = DaemonLauncher(root, env = mapOf("JAVA_HOME" to fakeHome.absolutePath))
                val ex = shouldThrow<DaemonStartException> { launcher.ensureRunning(timeoutMs = 500) }
                ex.message!!.contains("JDK 21") shouldBe true
            } finally {
                fakeHome.deleteRecursively()
            }
        }
    }

    @Test
    fun `the real java probe treats a java it cannot exec as no runtime`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "directory-as-exec semantics are POSIX")
        withProject { root ->
            val fakeHome = createTempDirectory("fakejdk2").toFile()
            try {
                // bin/java is a directory: it passes canExecute() (dirs are searchable) but ProcessBuilder
                // throws when it tries to exec it — exercising the probe's catch arm.
                File(fakeHome, "bin/java").mkdirs()
                val launcher = DaemonLauncher(root, env = mapOf("JAVA_HOME" to fakeHome.absolutePath))
                val ex = shouldThrow<DaemonStartException> { launcher.ensureRunning(timeoutMs = 500) }
                // The message must name JAVA_HOME (the set-but-broken branch), not the unset-PATH branch.
                ex.message!!.contains("JAVA_HOME") shouldBe true
            } finally {
                fakeHome.deleteRecursively()
            }
        }
    }

    @Test
    fun `parseJavaMajor reads the major version across banner formats`() {
        DaemonLauncher.parseJavaMajor("openjdk version \"21.0.2\" 2024-01-16") shouldBe 21
        DaemonLauncher.parseJavaMajor("java version \"1.8.0_401\"") shouldBe 8
        DaemonLauncher.parseJavaMajor("openjdk version \"17\" 2021-09-14") shouldBe 17
        DaemonLauncher.parseJavaMajor("openjdk version \"25-ea\" 2025-09-16") shouldBe 25
        DaemonLauncher.parseJavaMajor("no version token present") shouldBe null
    }

    @Test
    fun `respawn stops any existing daemon then waits for the new socket`() {
        withProject { root ->
            // A stale pid file with a pid that no longer exists exercises stopExisting's lookup path.
            DaemonEndpoint.pidFile(root).writeText("999999999")
            var server: AutoCloseable? = null
            try {
                val launcher =
                    DaemonLauncher(
                        root,
                        startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) },
                        javaVersionProbe = okJavaProbe,
                    )
                launcher.respawn(timeoutMs = 2_000) shouldBe true
            } finally {
                server?.close()
            }
        }
    }

    @Test
    fun `respawn uses the default timeout when the argument is omitted`() {
        withProject { root ->
            var server: AutoCloseable? = null
            try {
                // No timeout arg → the DEFAULT_SPAWN_TIMEOUT_MS default; the injected spawn binds the socket
                // immediately, so it returns true on the first poll rather than waiting out the default.
                val launcher =
                    DaemonLauncher(
                        root,
                        startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) },
                        javaVersionProbe = okJavaProbe,
                    )
                launcher.respawn() shouldBe true
            } finally {
                server?.close()
            }
        }
    }

    @Test
    fun `respawn destroys a live process recorded in the pid file`() {
        withProject { root ->
            // Spawn a harmless long-lived process, record its pid, and confirm respawn destroys it.
            // The identity seam answers true — the production check would (correctly) refuse `sleep`.
            val victim = ProcessBuilder(sleepCommand()).start()
            try {
                DaemonEndpoint.pidFile(root).writeText(victim.pid().toString())
                var server: AutoCloseable? = null
                try {
                    val launcher =
                        DaemonLauncher(
                            root,
                            startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) },
                            isDaemonProcess = { true },
                            javaVersionProbe = okJavaProbe,
                        )
                    launcher.respawn(timeoutMs = 2_000) shouldBe true
                    victim.waitFor(2, TimeUnit.SECONDS)
                    victim.isAlive shouldBe false
                } finally {
                    server?.close()
                }
            } finally {
                victim.destroyForcibly()
            }
        }
    }

    @Test
    fun `respawn refuses to signal a pid-file process that is not a ktrics daemon`() {
        withProject { root ->
            // A recycled pid can point at an unrelated process; the production identity check must
            // leave it alone (kill -9 skips the daemon's shutdown hook, so stale pid files happen).
            val bystander = ProcessBuilder(sleepCommand()).start()
            try {
                DaemonEndpoint.pidFile(root).writeText(bystander.pid().toString())
                var server: AutoCloseable? = null
                try {
                    // Default seam = the real looksLikeDaemon check; `sleep` is recognizably NOT ktricsd.
                    val launcher =
                        DaemonLauncher(
                            root,
                            startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) },
                            javaVersionProbe = okJavaProbe,
                        )
                    launcher.respawn(timeoutMs = 2_000) shouldBe true
                    bystander.isAlive shouldBe true // untouched
                } finally {
                    server?.close()
                }
            } finally {
                bystander.destroyForcibly()
            }
        }
    }

    @Test
    fun `respawn escalates to a forcible kill when the daemon ignores SIGTERM`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "no clean SIGTERM-trapping victim on Windows")
        withProject { root ->
            // A victim that traps and ignores SIGTERM and stays busy (stdin-independent, so the spawn pipe
            // can't EOF it early): destroy() is swallowed, so the grace window times out (awaitExit's
            // TimeoutException path) and stopExisting must escalate to destroyForcibly() (SIGKILL) — the path
            // the cooperative `sleep` victim skips. The inner `sleep` child is orphaned for <1s on the kill.
            val victim = ProcessBuilder("sh", "-c", "trap '' TERM; while :; do sleep 1; done").start()
            try {
                DaemonEndpoint.pidFile(root).writeText(victim.pid().toString())
                // Let the shell install its TERM trap before respawn fires SIGTERM, otherwise the signal can
                // race ahead of the trap and kill the victim on the default disposition — skipping escalation.
                Thread.sleep(500)
                var server: AutoCloseable? = null
                try {
                    val launcher =
                        DaemonLauncher(
                            root,
                            startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) },
                            isDaemonProcess = { true },
                            javaVersionProbe = okJavaProbe,
                        )
                    val elapsed = kotlin.system.measureTimeMillis { launcher.respawn(timeoutMs = 3_000) shouldBe true }
                    // The grace window must actually elapse before the forcible kill — proof the escalation
                    // path ran rather than the victim dying on the initial SIGTERM.
                    (elapsed >= 900) shouldBe true
                    victim.waitFor(2, TimeUnit.SECONDS)
                    victim.isAlive shouldBe false // survived SIGTERM, killed only by the forcible escalation
                } finally {
                    server?.close()
                }
            } finally {
                victim.destroyForcibly()
            }
        }
    }

    @Test
    fun `respawn treats an interrupted grace wait as exited and still escalates`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "no clean SIGTERM-trapping victim on Windows")
        withProject { root ->
            // Same trap-protected victim, but here the waiting thread is interrupted: awaitExit's blocking
            // get() throws InterruptedException (not TimeoutException), exercising its generic-exception arm,
            // which treats a still-live handle as "not exited" so stopExisting still escalates to a kill.
            val victim = ProcessBuilder("sh", "-c", "trap '' TERM; while :; do sleep 1; done").start()
            try {
                DaemonEndpoint.pidFile(root).writeText(victim.pid().toString())
                Thread.sleep(500) // let the TERM trap install first (see the escalation test)
                var server: AutoCloseable? = null
                try {
                    val launcher =
                        DaemonLauncher(
                            root,
                            startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) },
                            isDaemonProcess = { true },
                            javaVersionProbe = okJavaProbe,
                        )
                    // Pre-set the interrupt flag so the first awaitExit get() throws InterruptedException at once;
                    // the throw clears the flag, so the post-escalation wait and spawn complete normally.
                    Thread.currentThread().interrupt()
                    launcher.respawn(timeoutMs = 3_000) shouldBe true
                    victim.waitFor(2, TimeUnit.SECONDS)
                    victim.isAlive shouldBe false
                } finally {
                    server?.close()
                }
            } finally {
                victim.destroyForcibly()
            }
        }
    }

    private fun sleepCommand(): List<String> =
        if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("ping", "-n", "30", "127.0.0.1")
        } else {
            listOf("sleep", "30")
        }
}
