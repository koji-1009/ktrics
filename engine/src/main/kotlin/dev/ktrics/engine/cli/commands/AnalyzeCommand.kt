package dev.ktrics.engine.cli.commands

import dev.ktrics.config.KtricsConfig
import dev.ktrics.coverage.CoverageData
import dev.ktrics.coverage.JacocoParser
import dev.ktrics.engine.AnalysisEngine
import dev.ktrics.engine.GraphSource
import dev.ktrics.engine.ProjectInputs
import dev.ktrics.engine.ResolvedProject
import dev.ktrics.engine.WarmIndexCache
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandHandler
import dev.ktrics.engine.cli.Exit
import dev.ktrics.ir.UnusedEntry
import dev.ktrics.report.AnalysisReport
import dev.ktrics.report.FileSystemSourceProvider
import dev.ktrics.report.ReportSummary
import dev.ktrics.report.ReporterFormat
import dev.ktrics.report.Reporters
import dev.ktrics.unused.CallGraph
import dev.ktrics.unused.UnusedDetector
import dev.ktrics.vcs.GitClient
import dev.ktrics.vcs.GitRefException
import dev.ktrics.vcs.Regression
import dev.ktrics.vcs.SnapshotStore
import java.io.File

/**
 * `ktrics analyze <path>` — every enabled lens. Honours `--reporter`, `--output`,
 * `--coverage`, `--since`, `--limit`, `--no-auto-explain`, `--strict-dismiss`, `--fatal-warnings`.
 */
object AnalyzeCommand : CommandHandler {
    override fun run(ctx: CommandContext): Int {
        val target = ctx.firstPositional()?.let { resolve(ctx.projectRoot, it) } ?: ctx.projectRoot
        val format = parseFormat(ctx) ?: return Exit.USAGE
        val coverage = loadCoverage(ctx) ?: return Exit.BAD_INPUT
        val resolved = GraphSource.resolve(ctx, target)
        val snapshotMode = resolveSnapshotMode(ctx, resolved)
        val full =
            AnalysisEngine(
                projectRoot = ctx.projectRoot,
                settings = resolved.settings,
                skips = resolved.skips,
                configHash = resolved.configHash,
                strictDismiss = ctx.flag("--strict-dismiss"),
                coverage = coverage,
                resolved = resolved.resolved,
                includeMeasurements = snapshotMode != null,
                excludeGlobs = resolved.config.exclude,
            ).analyze(resolved.graph)

        if (snapshotMode != null) handleSnapshot(ctx, snapshotMode, full)
        // Enrich with the unused: + signals: blocks (sibling of dartrics) before --since/limit shaping.
        val enriched = enrichWithGraph(ctx, resolved, full)
        val report = postProcess(ctx, enriched) ?: return Exit.BAD_INPUT

        val sources = FileSystemSourceProvider(ctx.projectRoot)
        val limit = ctx.option("--limit")?.toIntOrNull()?.coerceAtLeast(0)
        val text = Reporters.forFormat(format, sources, autoExplain = !ctx.flag("--no-auto-explain"), limit = limit).render(report)
        writeOutput(ctx, format, text)
        return if (ctx.flag("--fatal-warnings") && report.violations.isNotEmpty()) Exit.VIOLATIONS else Exit.OK
    }

    private fun parseFormat(ctx: CommandContext): ReporterFormat? {
        val id = ctx.option("--reporter") ?: "console"
        return ReporterFormat.fromId(id) ?: run {
            ctx.sink.errLine("ktrics: unknown reporter '$id'. Choices: ${ReporterFormat.ids.joinToString()}.")
            null
        }
    }

    /**
     * The active snapshot mode, or null when snapshotting is off. `--snapshot <mode>` wins; absent, the
     * configured `snapshot.mode` is the fallback so the documented config field is honoured. `none` is
     * off in both. To preserve the historical "no snapshot unless explicitly requested" default, the
     * built-in default mode (SnapshotConfig's `"baseline"`, which an unconfigured project always
     * carries) does NOT activate snapshotting from config alone — only a config that sets a non-default
     * mode (e.g. `cache` or an explicit path) does, while `--snapshot baseline` still works.
     */
    private fun resolveSnapshotMode(
        ctx: CommandContext,
        resolved: ResolvedProject,
    ): String? {
        ctx.option("--snapshot")?.let { return it.takeUnless { m -> m.equals("none", ignoreCase = true) } }
        val configured = resolved.config.snapshot.mode
        if (configured.equals("none", ignoreCase = true)) return null
        // Don't snapshot on the built-in default — an unconfigured run must stay off, as it was before.
        if (configured == KtricsConfig.DEFAULT.snapshot.mode) return null
        return configured
    }

    private fun writeOutput(
        ctx: CommandContext,
        format: ReporterFormat,
        text: String,
    ) {
        val output = ctx.option("--output")
        if (output != null) {
            val file = ctx.resolvePath(output)
            // Create the parent dir so an explicit `--output dir/report.json` into a not-yet-existing
            // directory writes the file instead of throwing FileNotFoundException → INTERNAL (70).
            file.parentFile?.mkdirs()
            file.writeText(text)
            ctx.sink.errLine("ktrics: wrote ${format.id} report to $output")
        } else {
            ctx.sink.out(text)
            if (!text.endsWith("\n")) ctx.sink.out("\n")
        }
    }

    /**
     * Applies `--since` (filter to changed files); `--limit` (top-N) is the ai reporter's job now, so
     * json/sarif keep the full set for `report`/regression. Signals follow the kept violation scopes.
     */
    internal fun postProcess(
        ctx: CommandContext,
        report: AnalysisReport,
    ): AnalysisReport? {
        var violations = report.violations
        var unused = report.unused
        ctx.option("--since")?.let { ref ->
            val git = GitClient(ctx.projectRoot)
            val changed =
                try {
                    git.changedFilesSince(ref).toSet()
                } catch (e: GitRefException) {
                    ctx.sink.errLine("ktrics: ${e.message}")
                    return null // → exit 65
                }
            violations = violations.filter { it.file in changed }
            unused = unused.filter { it.file in changed }
        }
        val keptScopes = violations.map { it.scope }.toSet()
        val signals = report.signals.filter { it.scopeName in keptScopes }
        // Strip measurements before rendering — they are an internal channel for snapshot/regression,
        // kept out of the lean ai/json output.
        return report.copy(
            violations = violations,
            summary = ReportSummary.of(violations, report.files),
            measurements = emptyList(),
            unused = unused,
            signals = signals,
        )
    }

    /**
     * Runs the public-API reachability sweep + call graph and attaches the `unused:` block (full) and
     * the `signals:` block (scoped to the scopes that fired) — reference information for the loop,
     * matching dartrics' analyze report (the AI-loop playbook).
     */
    private fun enrichWithGraph(
        ctx: CommandContext,
        resolved: ResolvedProject,
        report: AnalysisReport,
    ): AnalysisReport {
        val warm = WarmIndexCache.get(ctx.projectRoot, resolved.graph, resolved.configHash, resolved.resolved)
        val classifierFor = ProjectInputs.classifierFor(ctx.projectRoot, resolved.resolved)
        val unused =
            UnusedDetector(warm.units, classifierFor, ProjectInputs.unusedConfig(resolved)).detect().unused
                .map { UnusedEntry(it.file, it.span.startLine, it.kind, it.displayName) }
        val graph = CallGraph.build(warm.units, classifierFor)
        val signals = report.violations.mapNotNull { graph.signalOf(it.scope) }.distinctBy { it.scopeName }
        return report.copy(unused = unused, signals = signals)
    }

    /** Diffs against a stored snapshot baseline and (for `baseline` mode) updates it. */
    private fun handleSnapshot(
        ctx: CommandContext,
        mode: String,
        report: AnalysisReport,
    ) {
        val store = SnapshotStore(ctx.projectRoot)
        val file = store.resolve(mode)
        store.load(file, expectedVersion = report.version)?.let { baseline ->
            val diff = Regression.compare(baseline.measurements, report.measurements)
            ctx.sink.errLine(
                "ktrics snapshot: ${diff.improved} improved, ${diff.regressed} regressed, " +
                    "${diff.added} added, ${diff.removed} removed since baseline.",
            )
            if (diff.cosmeticRefactorSuspected) ctx.sink.errLine("ktrics snapshot: cosmetic-refactor suspected.")
        }
        if (mode == "baseline" || (mode == "cache" && !file.isFile)) {
            store.save(file, report.version, report.measurements)
            ctx.sink.errLine("ktrics snapshot: baseline saved to ${file.path}")
        }
    }

    /** Loads `--coverage <path>` (JaCoCo XML), or empty for `none`/absent. Null on a bad path (→ 65). */
    @Suppress("TooGenericExceptionCaught")
    private fun loadCoverage(ctx: CommandContext): CoverageData? {
        val path = ctx.option("--coverage") ?: return CoverageData.EMPTY
        if (path.equals("none", ignoreCase = true)) return CoverageData.EMPTY
        val file = ctx.resolvePath(path)
        if (!file.isFile) {
            ctx.sink.errLine("ktrics: coverage file not found: $path")
            return null
        }
        // A present-but-malformed XML (SAXParseException, IOException, …) is bad input, not an internal
        // fault: catch it here so it maps to BAD_INPUT (65) via the same null path as a missing file,
        // rather than escaping to Cli.run and being reported as INTERNAL (70).
        return try {
            JacocoParser.parse(file)
        } catch (e: Exception) {
            ctx.sink.errLine("ktrics: could not parse coverage file '$path': ${e.message}")
            null
        }
    }

    private fun resolve(
        root: File,
        path: String,
    ): File {
        val f = File(path)
        return (if (f.isAbsolute) f else File(root, path)).absoluteFile.normalize()
    }
}
