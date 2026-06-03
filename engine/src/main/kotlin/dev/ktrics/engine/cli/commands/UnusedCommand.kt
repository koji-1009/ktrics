package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.GraphSource
import dev.ktrics.engine.ProjectInputs
import dev.ktrics.engine.WarmIndexCache
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandHandler
import dev.ktrics.engine.cli.Exit
import dev.ktrics.ir.Resolution
import dev.ktrics.unused.UnusedDetector
import dev.ktrics.unused.UnusedReport
import dev.ktrics.unused.UnusedSymbol
import dev.ktrics.vcs.GitClient
import java.io.File

/**
 * `ktrics unused [--apply]` — public-API reachability (sibling of dartrics' `unused`).
 * `--filter <kinds>` narrows the report by declaration kind; `--include-tests` widens it into test
 * trees (excluded by default). `--apply` deletes top-level orphans in place (git-recoverable) — but
 * only when reachability was FULLY resolution-backed (a name-based fallback would risk deleting live
 * code) AND the git tree is clean so the deletion lands in its own diff (override the latter with
 * `--force`). Reporters: `console` (default) and `ai`.
 */
object UnusedCommand : CommandHandler {
    override fun run(ctx: CommandContext): Int {
        val reporter = ctx.option("--reporter") ?: "console"
        if (reporter != "console" && reporter != "ai") {
            ctx.sink.errLine("ktrics: unused supports only --reporter console|ai (got '$reporter').")
            return Exit.USAGE
        }
        val target = ctx.firstPositional()?.let { resolveTarget(ctx, it) } ?: ctx.projectRoot
        val resolved = GraphSource.resolve(ctx, target)
        val warm = WarmIndexCache.get(ctx.projectRoot, resolved.graph, resolved.configHash, resolved.resolved)
        val classifierFor = ProjectInputs.classifierFor(ctx.projectRoot, resolved.resolved)
        val unusedConfig = ProjectInputs.unusedConfig(resolved, includeTests = ctx.flag("--include-tests"))

        val full = UnusedDetector(warm.units, classifierFor, unusedConfig).detect()
        val report = applyFilter(full, ctx.option("--filter"))

        if (reporter == "ai") renderAi(ctx, report) else renderConsole(ctx, report)

        if (ctx.flag("--apply")) return apply(ctx, report)
        return Exit.OK
    }

    /** Resolves a relative target against the project root (not the daemon's CWD), like `analyze`. */
    private fun resolveTarget(
        ctx: CommandContext,
        path: String,
    ): File {
        val f = File(path)
        return (if (f.isAbsolute) f else File(ctx.projectRoot, path)).absoluteFile.normalize()
    }

    /** Narrows the report to specific declaration kinds (`--filter method,class`), if given. */
    private fun applyFilter(
        report: UnusedReport,
        filter: String?,
    ): UnusedReport {
        val kinds = filter?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        if (kinds.isEmpty()) return report
        return report.copy(unused = report.unused.filter { it.kind.lowercase() in kinds })
    }

    private fun renderConsole(
        ctx: CommandContext,
        report: UnusedReport,
    ) {
        ctx.sink.outLine(
            "ktrics unused: ${report.unused.size} unreachable public symbol(s) " +
                "(${report.rootCount} roots, ${report.consideredCount} considered) [resolution: ${report.resolution.wireName}]",
        )
        report.unused.forEach { u ->
            ctx.sink.outLine("  ${u.file}:${u.span.startLine}  ${u.kind} ${u.displayName}  [${u.visibility.name.lowercase()}]")
        }
    }

    private fun renderAi(
        ctx: CommandContext,
        report: UnusedReport,
    ) {
        ctx.sink.outLine("# ktrics unused-report v1")
        ctx.sink.outLine("# Unused entries may be leftover code to delete OR unwired implementations")
        ctx.sink.outLine("# (declared but never reached — a possible wiring gap). Confirm against intent first.")
        ctx.sink.outLine("resolution: ${report.resolution.wireName}")
        if (report.unused.isEmpty()) {
            ctx.sink.outLine("unused: []")
            return
        }
        ctx.sink.outLine("unused:")
        report.unused.forEach { u ->
            ctx.sink.outLine("  - file: ${u.file}")
            ctx.sink.outLine("    line: ${u.span.startLine}")
            ctx.sink.outLine("    kind: ${u.kind}")
            ctx.sink.outLine("    name: ${u.displayName}")
        }
    }

    private fun apply(
        ctx: CommandContext,
        report: UnusedReport,
    ): Int {
        // Safety gate 1: never delete on name-based reachability — false positives would remove live code.
        if (report.resolution != Resolution.RESOLVED) {
            ctx.sink.errLine(
                "ktrics: --apply refused: reachability was name-based (classpath incomplete). " +
                    "Provide module classpaths so edges resolve, or review manually.",
            )
            return Exit.BAD_INPUT
        }
        val deletable = report.unused.filter { it.topLevel }
        if (deletable.isEmpty()) {
            ctx.sink.outLine("ktrics: nothing to delete (no top-level orphans).")
            return Exit.OK
        }
        // Safety gate 2: the deletion should land in its own diff, so refuse on a dirty tree (--force overrides).
        val git = GitClient(ctx.projectRoot)
        if (git.isRepository() && !git.isClean() && !ctx.flag("--force")) {
            ctx.sink.errLine(
                "ktrics: --apply refused: the git tree has uncommitted changes. " +
                    "Commit or stash first so the deletion is its own diff, or pass --force.",
            )
            return Exit.BAD_INPUT
        }
        val removed = excise(ctx.projectRoot, deletable)
        ctx.sink.outLine("ktrics: removed $removed top-level orphan(s) (recoverable via git).")
        return Exit.OK
    }

    /** Removes each orphan's source span, editing bottom-up per file so line numbers stay valid. */
    private fun excise(
        projectRoot: File,
        symbols: List<UnusedSymbol>,
    ): Int {
        var removed = 0
        symbols.groupBy { it.file }.forEach { (relPath, syms) ->
            val file = File(projectRoot, relPath)
            if (!file.isFile) return@forEach
            val raw = file.readText()
            val newline = if (raw.contains("\r\n")) "\r\n" else "\n"
            val hadTrailingNewline = raw.endsWith("\n")
            val lines = contentLines(raw, hadTrailingNewline)
            removed += removeSpans(lines, syms)
            // Preserve the original newline style and trailing newline rather than forcing LF + no-EOL.
            val body = lines.joinToString(newline)
            file.writeText(if (hadTrailingNewline && lines.isNotEmpty()) body + newline else body)
        }
        return removed
    }

    /**
     * Splits raw text into content lines with readLines semantics: a final newline yields a trailing
     * empty element from the split, which we drop so the 1-based line math in [removeSpans] is unchanged.
     */
    private fun contentLines(
        raw: String,
        hadTrailingNewline: Boolean,
    ): MutableList<String> {
        val lines = raw.split(Regex("\r\n|\n")).toMutableList()
        if (hadTrailingNewline && lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines
    }

    /** Removes each symbol's inclusive 1-based line span, bottom-up so earlier line numbers stay valid. */
    private fun removeSpans(
        lines: MutableList<String>,
        syms: List<UnusedSymbol>,
    ): Int {
        var removed = 0
        syms.sortedByDescending { it.span.startLine }.forEach { s ->
            val firstLine = s.span.startLine
            // A PSI range ending at column 1 stops at the START of endLine without occupying it;
            // removing through endLine would delete the following declaration's first line.
            var lastLine = s.span.endLine
            if (s.span.endColumn <= 1 && lastLine > firstLine) lastLine -= 1
            val from = (firstLine - 1).coerceIn(0, lines.size)
            val toExclusive = lastLine.coerceIn(from, lines.size) // remove inclusive [firstLine, lastLine]
            if (toExclusive > from) {
                repeat(toExclusive - from) { lines.removeAt(from) }
                removed++
            }
        }
        return removed
    }
}
