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
        // Mirror the client's root resolution: explicit `--root` wins, else walk up to the nearest
        // `ktrics.yaml` ancestor. A bare cwd fallback would miss the config when run from a subdirectory.
        val root = optionValue(argv, "--root")?.let { File(it) } ?: findProjectRoot(cwd)
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

    /**
     * Nearest ancestor of [start] containing `ktrics.yaml`/`ktrics.yml`, else [start] itself
     * (normalized). Mirrors the client's `resolveProjectRoot` so the daemon resolves the same project
     * root the client would, regardless of which subdirectory a command is run from.
     */
    fun findProjectRoot(start: File): File {
        var dir: File? = start.absoluteFile
        while (dir != null) {
            if (File(dir, "ktrics.yaml").isFile || File(dir, "ktrics.yml").isFile) return dir
            dir = dir.parentFile
        }
        return start.absoluteFile.normalize()
    }
}
