package dev.ktrics.client

import dev.ktrics.client.proto.ClientRequest
import dev.ktrics.client.proto.DaemonEndpoint
import dev.ktrics.client.proto.DaemonFrame
import dev.ktrics.client.proto.ExitCode
import dev.ktrics.client.proto.FrameCodec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.channels.Channels
import kotlin.io.path.createTempDirectory

/** The client's logic via [ClientCli] DI seams: fast paths, relay orchestration, and the pure helpers. */
class ClientCliTest {
    private val request = ClientRequest(argv = listOf("x"), cwd = "/x")

    private fun exited(code: Int) = DaemonClient.Outcome.Exited(code)

    // --- dispatch ---

    @Test
    fun `--version is handled locally without relaying`() {
        var ran = false
        val code =
            ClientCli.dispatch(listOf("--version"), runner = { _, _ ->
                ran = true
                99
            })
        code shouldBe ExitCode.OK.code
        ran shouldBe false
    }

    @Test
    fun `a normal command assembles a request and delegates to the runner`() {
        var seen: ClientRequest? = null
        val cwd = File("/work")
        val code =
            ClientCli.dispatch(
                listOf("analyze", "src/"),
                cwd = cwd,
                env = mapOf("PATH" to "/bin", "SECRET" to "x"),
                runner = { _, req ->
                    seen = req
                    0
                },
            )
        code shouldBe 0
        seen!!.argv shouldBe listOf("analyze", "src/")
        seen!!.env.containsKey("PATH") shouldBe true
        seen!!.env.containsKey("SECRET") shouldBe false
    }

    @Test
    fun `dispatch relays the cwd and the resolved project root to the runner`() {
        var seenRoot: File? = null
        var seenReq: ClientRequest? = null
        val cwd = createTempDirectory("cli").toFile()
        try {
            ClientCli.dispatch(
                listOf("analyze"),
                cwd = cwd,
                runner = { root, req ->
                    seenRoot = root
                    seenReq = req
                    0
                },
            )
            // The request carries the cwd, and the runner receives the RESOLVED project root (no ktrics.yaml
            // here, so it resolves to the cwd) — proving dispatch wires resolveProjectRoot through, not a raw arg.
            seenReq!!.cwd shouldBe cwd.absolutePath
            seenRoot shouldBe ClientCli.resolveProjectRoot(listOf("analyze"), cwd)
        } finally {
            cwd.deleteRecursively()
        }
    }

    // --- relay orchestration ---

    @Test
    fun `relay returns INTERNAL when the daemon cannot be started`() {
        ClientCli.relay(ensureRunning = { false }, respawn = { true }, relayOnce = { exited(0) }) shouldBe
            ExitCode.INTERNAL.code
    }

    @Test
    fun `relay returns the daemon exit code on a clean run`() {
        ClientCli.relay(ensureRunning = { true }, respawn = { true }, relayOnce = { exited(5) }) shouldBe 5
    }

    @Test
    fun `a version mismatch respawns and retries once`() {
        val outcomes = ArrayDeque(listOf(DaemonClient.Outcome.VersionMismatch(9), exited(3)))
        var respawned = false
        val code =
            ClientCli.relay(
                ensureRunning = { true },
                respawn = {
                    respawned = true
                    true
                },
                relayOnce = { outcomes.removeFirst() },
            )
        code shouldBe 3
        respawned shouldBe true
    }

    @Test
    fun `a failed respawn after a mismatch is INTERNAL`() {
        ClientCli.relay(
            ensureRunning = { true },
            respawn = { false },
            relayOnce = { DaemonClient.Outcome.VersionMismatch(9) },
        ) shouldBe ExitCode.INTERNAL.code
    }

    @Test
    fun `a non-exit retry after a mismatch is INTERNAL`() {
        val outcomes =
            ArrayDeque<DaemonClient.Outcome>(
                listOf(DaemonClient.Outcome.VersionMismatch(9), DaemonClient.Outcome.Unreachable),
            )
        ClientCli.relay(ensureRunning = { true }, respawn = { true }, relayOnce = { outcomes.removeFirst() }) shouldBe
            ExitCode.INTERNAL.code
    }

    @Test
    fun `an unreachable daemon is INTERNAL`() {
        ClientCli.relay(ensureRunning = { true }, respawn = { true }, relayOnce = { DaemonClient.Outcome.Unreachable }) shouldBe
            ExitCode.INTERNAL.code
    }

    @Test
    fun `dispatch without a runner uses the default that relays through run`() {
        val root = createTempDirectory("clidisp").toFile()
        val socket = DaemonEndpoint.socketPath(root)
        val server =
            TestUnixServer.start(socket) { ch ->
                FrameCodec.readRequest(DataInputStream(Channels.newInputStream(ch).buffered()))
                FrameCodec.writeFrame(DataOutputStream(Channels.newOutputStream(ch).buffered()), DaemonFrame.Exit(0))
            }
        try {
            // No runner argument → the default `{ root, req -> run(root, req) }` relays to the live daemon.
            ClientCli.dispatch(listOf("analyze"), cwd = root) shouldBe 0
        } finally {
            server.close()
            socket.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun `run wires a real launcher and client end-to-end`() {
        val root = createTempDirectory("clientrun").toFile()
        val socket = DaemonEndpoint.socketPath(root)
        val server =
            TestUnixServer.start(socket) { ch ->
                FrameCodec.readRequest(DataInputStream(Channels.newInputStream(ch).buffered()))
                FrameCodec.writeFrame(DataOutputStream(Channels.newOutputStream(ch).buffered()), DaemonFrame.Exit(0))
            }
        try {
            // The socket is already up, so the real launcher's ensureRunning short-circuits to true.
            ClientCli.run(root, request) shouldBe 0
        } finally {
            server.close()
            socket.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun `run reports an actionable error and exits INTERNAL when the daemon runtime is unusable`() {
        val root = createTempDirectory("nojava").toFile()
        try {
            // No socket up + a preflight that finds no usable Java → ensureRunning throws
            // DaemonStartException; run must catch it and exit INTERNAL rather than let it propagate.
            val launcher = DaemonLauncher(root, env = emptyMap(), javaVersionProbe = { null })
            val client = DaemonClient(DaemonEndpoint.socketPath(root))
            ClientCli.run(root, request, launcher = launcher, client = client) shouldBe ExitCode.INTERNAL.code
        } finally {
            runCatching { DaemonEndpoint.socketPath(root).delete() }
            root.deleteRecursively()
        }
    }

    // --- pure helpers ---

    @Test
    fun `resolveProjectRoot honours an explicit --root`() {
        ClientCli.resolveProjectRoot(listOf("--root", "/work/p"), File("/elsewhere")) shouldBe
            File("/work/p").absoluteFile.normalize()
    }

    @Test
    fun `resolveProjectRoot finds the nearest ktrics-yaml ancestor`() {
        val root = createTempDirectory("proj").toFile()
        try {
            File(root, "ktrics.yaml").writeText("ktrics: {}\n")
            val nested = File(root, "a/b").apply { mkdirs() }
            ClientCli.resolveProjectRoot(emptyList(), nested) shouldBe root.absoluteFile
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveProjectRoot falls back to the cwd`() {
        val cwd = createTempDirectory("nocfg").toFile()
        try {
            ClientCli.resolveProjectRoot(emptyList(), cwd) shouldBe cwd.absoluteFile.normalize()
        } finally {
            cwd.deleteRecursively()
        }
    }

    @Test
    fun `relayEnv keeps only the curated keys and the ktrics namespace`() {
        val env = ClientCli.relayEnv(mapOf("PATH" to "/b", "HOME" to "/h", "KTRICS_X" to "1", "SECRET" to "s"))
        env.keys shouldBe setOf("PATH", "HOME", "KTRICS_X")
    }
}
