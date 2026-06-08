package dev.ktrics.daemon

import dev.ktrics.client.proto.ExitCode
import dev.ktrics.client.proto.Protocol
import dev.ktrics.engine.cli.Cli
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandSink
import java.io.File

/**
 * Parses a relayed argv and runs the matching command (CLI surface). The daemon owns the
 * version handshake and daemon lifecycle; everything else delegates to the engine-side [Cli], whose
 * handler set expands over time. The native client ↔ daemon loop is demonstrable from the start.
 */
class CommandRouter(
    private val server: DaemonControl,
    /**
     * The resolved project root this daemon serves — the root the client walked up to (nearest
     * `ktrics.yaml` ancestor) and spawned us with via `--serve --root`. A daemon's socket is 1:1 with
     * this root, so it is the canonical project root for EVERY request on this socket. Used as the
     * default when a request carries no explicit `--root`: the relayed `cwd` is the client's raw
     * working directory (a subdirectory, in the common case), and resolving config/module-graph from
     * it would silently miss the project's `ktrics.yaml`.
     */
    private val serveRoot: File,
    private val cli: Cli = Cli.default(),
) {
    /** Minimal control surface the router needs from the running daemon. */
    interface DaemonControl {
        fun requestShutdown()

        fun status(): String
    }

    fun dispatch(
        argv: List<String>,
        cwd: String,
        env: Map<String, String>,
        sink: CommandSink,
    ): Int {
        val root = projectRoot(argv)
        return when (val command = argv.firstOrNull()) {
            null, "--help", "-h" -> {
                sink.out(usage())
                ExitCode.OK.code
            }
            "--version", "-V" -> {
                sink.outLine("ktrics ${BuildInfo.VERSION} (daemon, protocol v${Protocol.VERSION})")
                ExitCode.OK.code
            }
            "daemon" -> daemon(argv.drop(1), sink)
            else -> {
                val ctx =
                    CommandContext(
                        args = argv.drop(1),
                        projectRoot = root,
                        env = env,
                        sink = sink,
                        cwd = File(cwd).absoluteFile.normalize(),
                    )
                if (cli.supports(command)) {
                    cli.run(command, ctx)
                } else {
                    sink.errLine("ktrics: unknown command '$command'. Try `ktrics --help`.")
                    ExitCode.USAGE.code
                }
            }
        }
    }

    private fun daemon(
        args: List<String>,
        sink: CommandSink,
    ): Int =
        when (args.firstOrNull()) {
            "status" -> {
                sink.outLine(server.status())
                ExitCode.OK.code
            }
            "stop" -> {
                sink.outLine("ktrics: stopping daemon.")
                server.requestShutdown()
                ExitCode.OK.code
            }
            else -> {
                sink.errLine("ktrics: usage: ktrics daemon status|stop")
                ExitCode.USAGE.code
            }
        }

    private fun projectRoot(argv: List<String>): File {
        // Explicit `--root` always wins; otherwise the daemon's canonical serve root, NOT the relayed
        // cwd — the client resolved the project root by walking up to `ktrics.yaml` and spawned us with
        // it, so falling back to cwd here would re-derive a subdirectory and silently ignore the config.
        val explicit = argv.zipWithNext().firstOrNull { it.first == "--root" }?.second
        return if (explicit != null) File(explicit).absoluteFile.normalize() else serveRoot
    }

    private fun usage(): String =
        buildString {
            appendLine("ktrics ${BuildInfo.VERSION} — code-quality metrics for Java and Kotlin")
            appendLine()
            appendLine("USAGE: ktrics <command> [options] <path>")
            appendLine()
            appendLine("COMMANDS:")
            appendLine("  analyze <path>      every enabled lens + unused detector")
            appendLine("  unused [--apply]    public-API reachability")
            appendLine("  regression          diff two refs by polarity")
            appendLine("  report <input.json> re-emit a saved report in another format")
            appendLine("  rules               full metric catalogue")
            appendLine("  inspect <symbol>    walk the resolved call graph around a declaration")
            appendLine("  manual              operator's manual")
            appendLine("  ai-loop             loop walkthrough")
            appendLine("  doctor              validate ktrics.yaml")
            appendLine("  daemon status|stop  daemon lifecycle")
            appendLine("  --version           print version")
        }
}
