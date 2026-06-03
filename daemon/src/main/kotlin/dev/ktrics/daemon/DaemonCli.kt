package dev.ktrics.daemon

import dev.ktrics.engine.cli.Cli
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandSink
import java.io.File

/**
 * The daemon's one-shot (cold, `--no-daemon`) command logic, separated from the JVM `main` shell so it
 * is fully testable. `main` in Main.kt handles only the blocking `--serve` socket loop and the
 * `exitProcess` boundary; everything a one-shot run is implemented here.
 */
internal object DaemonCli {
    /** One-shot run with no socket: output goes to [sink] (the process streams in production). */
    fun runForeground(
        argv: List<String>,
        sink: CommandSink = StreamSink(),
        env: Map<String, String> = System.getenv(),
        cwd: File = File(System.getProperty("user.dir")).absoluteFile.normalize(),
        cli: Cli = Cli.default(),
    ): Int {
        val root = optionValue(argv, "--root")?.let { File(it) } ?: cwd
        return when (val command = argv.firstOrNull()) {
            null, "--help", "-h" -> {
                sink.outLine("ktricsd ${BuildInfo.VERSION} — run with --serve, or pass a command for a cold one-shot run.")
                0
            }
            "--version", "-V" -> {
                sink.outLine("ktrics ${BuildInfo.VERSION} (daemon one-shot)")
                0
            }
            else -> {
                val ctx = CommandContext(argv.drop(1), root.absoluteFile.normalize(), env, sink, cwd)
                if (cli.supports(command)) {
                    cli.run(command, ctx)
                } else {
                    sink.errLine("ktrics: unknown command '$command'.")
                    64
                }
            }
        }
    }

    /** Value of `--name value` in [argv], or null. */
    fun optionValue(
        argv: List<String>,
        name: String,
    ): String? = argv.zipWithNext().firstOrNull { it.first == name }?.second
}
