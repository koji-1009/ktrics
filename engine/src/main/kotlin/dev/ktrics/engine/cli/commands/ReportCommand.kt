package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandHandler
import dev.ktrics.engine.cli.Exit
import dev.ktrics.report.FileSystemSourceProvider
import dev.ktrics.report.JsonReporter
import dev.ktrics.report.ReporterFormat
import dev.ktrics.report.Reporters

/**
 * `ktrics report <input.json>` — re-emit a saved JSON report in another format. Lets a loop
 * run analysis once and render console/md/ai/sarif from the same data without re-analyzing.
 */
object ReportCommand : CommandHandler {
    override fun run(ctx: CommandContext): Int {
        val input =
            ctx.firstPositional() ?: run {
                ctx.sink.errLine("ktrics: usage: ktrics report <input.json> [--reporter ai|md|console|sarif]")
                return Exit.USAGE
            }
        val file = ctx.resolvePath(input)
        if (!file.isFile) {
            ctx.sink.errLine("ktrics: no such report file: $input")
            return Exit.BAD_INPUT
        }
        val report =
            runCatching { JsonReporter.parse(file.readText()) }.getOrElse {
                ctx.sink.errLine("ktrics: could not parse report: ${it.message}")
                return Exit.BAD_INPUT
            }
        val format =
            ReporterFormat.fromId(ctx.option("--reporter") ?: "ai") ?: run {
                ctx.sink.errLine("ktrics: unknown reporter. Choices: ${ReporterFormat.ids.joinToString()}.")
                return Exit.USAGE
            }
        // Resolve the saved report's `root` against the client cwd, not the daemon's process CWD: a
        // relative root (the common case) must point at the client's project root. resolvePath leaves
        // an absolute root untouched.
        val sources = FileSystemSourceProvider(ctx.resolvePath(report.root))
        val text = Reporters.forFormat(format, sources).render(report)
        ctx.sink.out(text)
        if (!text.endsWith("\n")) ctx.sink.out("\n")
        return Exit.OK
    }
}
