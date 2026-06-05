package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.AnalysisEngine
import dev.ktrics.engine.GraphSource
import dev.ktrics.engine.WarmIndexCache
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandHandler
import dev.ktrics.engine.cli.Exit
import dev.ktrics.metric.MetricResult
import dev.ktrics.vcs.Change
import dev.ktrics.vcs.GitClient
import dev.ktrics.vcs.GitRefException
import dev.ktrics.vcs.Regression
import dev.ktrics.vcs.RegressionReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

/**
 * `ktrics regression --before <ref> --after <ref>`. Analyzes each ref in a detached
 * worktree, then diffs the measurements per-scope per-metric, classified by polarity, with the
 * cosmetic-refactor heuristic. Unresolved ref → exit 65.
 */
object RegressionCommand : CommandHandler {
    // The entry point is a chain of input-validation guard clauses (bad flags, not-a-repo, bad refs),
    // each returning a distinct sysexits code — the canonical use of multiple returns.
    @Suppress("ReturnCount")
    override fun run(ctx: CommandContext): Int {
        val before = ctx.option("--before") ?: return usage(ctx)
        val after = ctx.option("--after") ?: return usage(ctx)
        val reporter = ctx.option("--reporter") ?: "console"
        if (reporter !in REPORTERS) {
            ctx.sink.errLine("ktrics: regression supports --reporter ${REPORTERS.joinToString("|")} (got '$reporter').")
            return Exit.USAGE
        }
        val git = GitClient(ctx.projectRoot)
        if (!git.isRepository()) {
            ctx.sink.errLine("ktrics: not a git repository; regression needs git history.")
            return Exit.BAD_INPUT
        }
        val keep = metricFilter(ctx)
        val beforeMeasurements = measureFiltered(ctx, git, before, keep) ?: return Exit.BAD_INPUT
        val afterMeasurements = measureFiltered(ctx, git, after, keep) ?: return Exit.BAD_INPUT

        val report = Regression.compare(beforeMeasurements, afterMeasurements)
        return emit(ctx, renderReport(reporter, before, after, report))
    }

    private fun renderReport(
        reporter: String,
        before: String,
        after: String,
        report: RegressionReport,
    ): String =
        when (reporter) {
            "ai" -> renderAi(before, after, report)
            "json" -> renderJson(before, after, report)
            else -> render(before, after, report)
        }

    /**
     * `--metric a,b` restricts the diff to those metric ids (repeatable or comma-separated). Both
     * option forms are honoured: the inline `--metric=a,b` token and the two-token `--metric a,b`
     * pair — the same both-forms handling the shared option parser uses, so neither is silently
     * dropped (which would keep all metrics).
     */
    private fun metricFilter(ctx: CommandContext): (MetricResult) -> Boolean {
        val inline =
            ctx.args.asSequence()
                .filter { it.startsWith("--metric=") }
                .map { it.substringAfter('=') }
        val paired =
            ctx.args.asSequence()
                .zipWithNext().filter { it.first == "--metric" }
                .map { it.second }
        val ids =
            (inline + paired)
                .flatMap { it.split(',').asSequence() }
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return { ids.isEmpty() || it.metricId in ids }
    }

    /** Analyzes [ref], keeping only the filtered measurements; emits a message and returns null on a bad ref. */
    private fun measureFiltered(
        ctx: CommandContext,
        git: GitClient,
        ref: String,
        keep: (MetricResult) -> Boolean,
    ): List<MetricResult>? =
        try {
            measureAt(ctx, git, ref).filter(keep)
        } catch (e: GitRefException) {
            ctx.sink.errLine("ktrics: ${e.message}")
            null
        }

    private fun emit(
        ctx: CommandContext,
        text: String,
    ): Int {
        val output = ctx.option("--output")
        if (output != null) {
            val file = ctx.resolvePath(output)
            // Create the parent dir so an `--output` into a not-yet-existing directory writes the file
            // instead of throwing FileNotFoundException → INTERNAL (70).
            file.parentFile?.mkdirs()
            file.writeText(text)
            ctx.sink.errLine("ktrics: wrote regression report to $output")
        } else {
            ctx.sink.out(text)
            if (!text.endsWith("\n")) ctx.sink.out("\n")
        }
        return Exit.OK
    }

    /** Analyze [ref] in a throwaway worktree; returns its full measurement set. */
    private fun measureAt(
        ctx: CommandContext,
        git: GitClient,
        ref: String,
    ): List<MetricResult> {
        val worktree = Files.createTempDirectory("ktrics-rev-").toFile()
        return try {
            git.addWorktree(worktree, ref)
            // A worktree checks out the WHOLE repository. When the project lives in a repo
            // subdirectory, the historical side must analyze the matching subtree — and load ITS
            // ktrics.yaml — or the diff compares a different file set under a different config
            // (the dartrics 0.8.0 `--root` defect class), with paths that never line up.
            val historicalRoot = subtreePath(ctx, git)?.let { File(worktree, it) } ?: worktree
            val pseudoCtx = ctx.copy(args = ctx.args, projectRoot = historicalRoot)
            val resolved = GraphSource.resolve(pseudoCtx, historicalRoot)
            // Historical config problems are surfaced but never fatal here: measurements are raw
            // values (thresholds don't shape them), so a broken config AT THE REF degrades to default
            // enablement rather than killing the comparison.
            GraphSource.reportConfig(pseudoCtx, resolved)
            AnalysisEngine(
                projectRoot = historicalRoot,
                settings = resolved.settings,
                skips = resolved.skips,
                // never share warm cache across refs
                configHash = resolved.configHash + ref,
                includeMeasurements = true,
                resolved = resolved.resolved,
            ).analyze(resolved.graph).measurements
        } finally {
            // The worktree dir never repeats, so its cache entry would otherwise leak a live session
            // (an IntelliJ application + all PSI) per regression run, forever, in a warm daemon.
            WarmIndexCache.evict(worktree)
            git.removeWorktree(worktree)
            worktree.deleteRecursively()
        }
    }

    /** The project root's path relative to the repo toplevel, or null when it IS the toplevel. */
    private fun subtreePath(
        ctx: CommandContext,
        git: GitClient,
    ): String? =
        runCatching {
            val toplevel = git.topLevel()?.canonicalFile ?: return null
            val root = ctx.projectRoot.canonicalFile
            if (root == toplevel) return null
            root.relativeTo(toplevel).path.takeIf { it.isNotEmpty() && !it.startsWith("..") }
        }.getOrNull()

    private fun render(
        before: String,
        after: String,
        r: RegressionReport,
    ): String =
        buildString {
            appendLine("ktrics regression: $before → $after")
            appendLine(
                "  improved: ${r.improved}  regressed: ${r.regressed}  unchanged: ${r.unchanged}  " +
                    "neutral-delta: ${r.neutralDelta}  added: ${r.added}  removed: ${r.removed}",
            )
            if (r.cosmeticSplitDetected) {
                appendLine(
                    "  cosmetic-split signature detected: many tiny helpers added, lines up, complexity barely down" +
                        " (narrow heuristic, not a global verdict).",
                )
            }
            val notable = r.entries.filter { it.change == Change.REGRESSED || it.change == Change.IMPROVED }
            if (notable.isNotEmpty()) {
                appendLine()
                appendLine("  changes:")
                notable.forEach { e ->
                    val arrow = if (e.change == Change.IMPROVED) "↓" else "↑"
                    appendLine("    [${e.change.name.lowercase().padEnd(9)}] $arrow ${e.metric}  ${e.scope}  ${e.before} → ${e.after}")
                }
            }
        }

    /** YAML-ish report for the AI loop: summary + per-(scope,metric) changes by polarity. */
    private fun renderAi(
        before: String,
        after: String,
        r: RegressionReport,
    ): String =
        buildString {
            appendLine("# ktrics regression-report v1")
            appendLine("before: $before")
            appendLine("after: $after")
            appendLine("summary:")
            appendLine("  improved: ${r.improved}")
            appendLine("  regressed: ${r.regressed}")
            appendLine("  unchanged: ${r.unchanged}")
            appendLine("  neutralDelta: ${r.neutralDelta}")
            appendLine("  added: ${r.added}")
            appendLine("  removed: ${r.removed}")
            appendLine("# narrow heuristic, not a global verdict — false only means the cosmetic-split")
            appendLine("# signature (many tiny helpers, lines up, complexity flat) did not match.")
            appendLine("cosmeticSplitDetected: ${r.cosmeticSplitDetected}")
            val notable = r.entries.filter { it.change == Change.REGRESSED || it.change == Change.IMPROVED }
            if (notable.isEmpty()) {
                appendLine("changes: []")
                return@buildString
            }
            appendLine("changes:")
            notable.forEach { e ->
                appendLine("  - change: ${e.change.name.lowercase()}")
                appendLine("    metric: ${e.metric}")
                appendLine("    scope: ${e.scope}")
                appendLine("    file: ${e.file}")
                appendLine("    before: ${e.before}")
                appendLine("    after: ${e.after}")
            }
        }

    private fun renderJson(
        before: String,
        after: String,
        r: RegressionReport,
    ): String {
        val obj =
            buildJsonObject {
                put("before", before)
                put("after", after)
                put(
                    "summary",
                    buildJsonObject {
                        put("improved", r.improved)
                        put("regressed", r.regressed)
                        put("unchanged", r.unchanged)
                        put("neutralDelta", r.neutralDelta)
                        put("added", r.added)
                        put("removed", r.removed)
                    },
                )
                put("cosmeticSplitDetected", r.cosmeticSplitDetected)
                put(
                    "changes",
                    JsonArray(
                        r.entries.map { e ->
                            buildJsonObject {
                                put("change", e.change.name.lowercase())
                                put("metric", e.metric)
                                put("scope", e.scope)
                                put("file", e.file)
                                put("before", e.before?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("after", e.after?.let { JsonPrimitive(it) } ?: JsonNull)
                            }
                        },
                    ),
                )
            }
        return PRETTY.encodeToString(JsonObject.serializer(), obj)
    }

    private fun usage(ctx: CommandContext): Int {
        ctx.sink.errLine("ktrics: usage: ktrics regression --before <ref> --after <ref> [--reporter ai|json|console] [--metric m,…]")
        return Exit.USAGE
    }

    private val REPORTERS = setOf("console", "ai", "json")
    private val PRETTY = Json { prettyPrint = true }
}
