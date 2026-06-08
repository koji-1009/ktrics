package dev.ktrics.daemon

import dev.ktrics.client.proto.ExitCode
import dev.ktrics.client.proto.Protocol
import dev.ktrics.engine.cli.Cli
import dev.ktrics.engine.cli.CommandHandler
import dev.ktrics.engine.cli.CommandSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/** Router contract: handshake, lifecycle, --root resolution, and honest unknown-command handling. */
class CommandRouterTest {
    private class CapturingSink : CommandSink {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        override fun out(text: String) {
            stdout.append(text)
        }

        override fun err(text: String) {
            stderr.append(text)
        }
    }

    private class FakeControl(private val statusLine: String = "ktricsd: 1 session, warm") :
        CommandRouter.DaemonControl {
        var shutdownRequested = false

        override fun requestShutdown() {
            shutdownRequested = true
        }

        override fun status(): String = statusLine
    }

    private fun router(
        control: CommandRouter.DaemonControl,
        cli: Cli = Cli.default(),
        serveRoot: File = File("/serve/root").absoluteFile.normalize(),
    ) = CommandRouter(control, serveRoot, cli)

    @Test
    fun `--version reports the daemon build and protocol version`() {
        val sink = CapturingSink()
        val code = router(FakeControl()).dispatch(listOf("--version"), cwd = ".", env = emptyMap(), sink = sink)
        code shouldBe ExitCode.OK.code
        sink.stdout.toString() shouldContain "protocol v${Protocol.VERSION}"
    }

    @Test
    fun `no command prints usage that advertises the inspect command`() {
        val sink = CapturingSink()
        val code = router(FakeControl()).dispatch(emptyList(), cwd = ".", env = emptyMap(), sink = sink)
        code shouldBe ExitCode.OK.code
        val usage = sink.stdout.toString()
        usage shouldContain "inspect"
        usage shouldContain "analyze"
        usage shouldContain "USAGE:"
    }

    @Test
    fun `--help also yields usage`() {
        val sink = CapturingSink()
        router(FakeControl()).dispatch(listOf("--help"), cwd = ".", env = emptyMap(), sink = sink)
        sink.stdout.toString() shouldContain "COMMANDS:"
    }

    @Test
    fun `an unknown command is a usage error written to stderr`() {
        val sink = CapturingSink()
        val code =
            router(FakeControl()).dispatch(
                listOf("frobnicate"),
                cwd = ".",
                env = emptyMap(),
                sink = sink,
            )
        code shouldBe ExitCode.USAGE.code
        sink.stderr.toString() shouldContain "unknown command 'frobnicate'"
    }

    @Test
    fun `daemon status delegates to the control surface`() {
        val sink = CapturingSink()
        val control = FakeControl(statusLine = "ktricsd: 3 sessions")
        val code = router(control).dispatch(listOf("daemon", "status"), cwd = ".", env = emptyMap(), sink = sink)
        code shouldBe ExitCode.OK.code
        sink.stdout.toString() shouldContain "3 sessions"
    }

    @Test
    fun `daemon stop requests shutdown`() {
        val sink = CapturingSink()
        val control = FakeControl()
        val code = router(control).dispatch(listOf("daemon", "stop"), cwd = ".", env = emptyMap(), sink = sink)
        code shouldBe ExitCode.OK.code
        control.shutdownRequested shouldBe true
        sink.stdout.toString() shouldContain "stopping daemon"
    }

    @Test
    fun `daemon with a bad subcommand is a usage error`() {
        val sink = CapturingSink()
        val code =
            router(FakeControl()).dispatch(
                listOf("daemon", "wat"),
                cwd = ".",
                env = emptyMap(),
                sink = sink,
            )
        code shouldBe ExitCode.USAGE.code
        sink.stderr.toString() shouldContain "usage: ktrics daemon status|stop"
    }

    @Test
    fun `--root overrides cwd as the project root passed to the command`() {
        // Inject a probe command that echoes the resolved project root so we can observe --root.
        val sink = CapturingSink()
        val probe =
            Cli(
                mapOf(
                    "probe" to
                        CommandHandler { ctx ->
                            ctx.sink.out(ctx.projectRoot.path)
                            0
                        },
                ),
            )
        router(FakeControl(), probe).dispatch(
            listOf("probe", "--root", "/work/elsewhere"),
            cwd = "/spawn/dir",
            env = emptyMap(),
            sink = sink,
        )
        sink.stdout.toString() shouldBe File("/work/elsewhere").absoluteFile.normalize().path
    }

    @Test
    fun `without --root the daemon's serve root - not the relayed cwd - is the project root`() {
        // The client walks up to the project's `ktrics.yaml` and spawns the daemon with that root; a
        // command relayed from a subdirectory carries that subdir as cwd. The router must resolve the
        // project root to the serve root, NOT the cwd, or the project's config is silently ignored.
        val sink = CapturingSink()
        val serveRoot = File("/project/root").absoluteFile.normalize()
        val probe =
            Cli(
                mapOf(
                    "probe" to
                        CommandHandler { ctx ->
                            ctx.sink.out(ctx.projectRoot.path)
                            0
                        },
                ),
            )
        router(FakeControl(), probe, serveRoot).dispatch(
            listOf("probe"),
            cwd = "/project/root/sub/dir",
            env = emptyMap(),
            sink = sink,
        )
        sink.stdout.toString() shouldBe serveRoot.path
    }

    @Test
    fun `a relayed env and tail args reach the command context`() {
        val sink = CapturingSink()
        val probe =
            Cli(
                mapOf(
                    "probe" to
                        CommandHandler { ctx ->
                            ctx.sink.out(ctx.args.joinToString(",") + "|" + ctx.env["FOO"])
                            0
                        },
                ),
            )
        router(FakeControl(), probe).dispatch(
            listOf("probe", "a", "b"),
            cwd = ".",
            env = mapOf("FOO" to "bar"),
            sink = sink,
        )
        sink.stdout.toString() shouldBe "a,b|bar"
    }
}
