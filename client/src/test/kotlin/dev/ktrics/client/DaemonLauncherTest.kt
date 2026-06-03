package dev.ktrics.client

import dev.ktrics.client.proto.DaemonEndpoint
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

/** Daemon auto-spawn orchestration, driven through the injected process-spawn seam. */
class DaemonLauncherTest {
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
                val launcher = DaemonLauncher(root, startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) })
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
            val launcher = DaemonLauncher(root, startProcess = { /* never binds a socket */ })
            launcher.ensureRunning(timeoutMs = 150) shouldBe false
        }
    }

    @Test
    fun `ensureRunning times out to false when the spawn never produces a socket`() {
        withProject { root ->
            val launcher = DaemonLauncher(root, startProcess = { /* no socket ever appears */ })
            launcher.ensureRunning(timeoutMs = 150) shouldBe false
        }
    }

    @Test
    fun `spawn assembles --serve, --root, the detach env var, and discards output`() {
        withProject { root ->
            var captured: ProcessBuilder? = null
            val launcher = DaemonLauncher(root, startProcess = { captured = it })
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
    fun `respawn stops any existing daemon then waits for the new socket`() {
        withProject { root ->
            // A stale pid file with a pid that no longer exists exercises stopExisting's lookup path.
            DaemonEndpoint.pidFile(root).writeText("999999999")
            var server: AutoCloseable? = null
            try {
                val launcher = DaemonLauncher(root, startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) })
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
                val launcher = DaemonLauncher(root, startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) })
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
            val victim = ProcessBuilder(sleepCommand()).start()
            try {
                DaemonEndpoint.pidFile(root).writeText(victim.pid().toString())
                var server: AutoCloseable? = null
                try {
                    val launcher = DaemonLauncher(root, startProcess = { server = TestUnixServer.start(DaemonEndpoint.socketPath(root)) })
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

    private fun sleepCommand(): List<String> =
        if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("ping", "-n", "30", "127.0.0.1")
        } else {
            listOf("sleep", "30")
        }
}
