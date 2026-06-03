package dev.ktrics.engine

import dev.ktrics.coverage.CoverageData
import dev.ktrics.dismiss.DismissalApplier
import dev.ktrics.dismiss.Sidecar
import dev.ktrics.frontend.java.JavaFrontend
import dev.ktrics.frontend.kotlin.KotlinFrontend
import dev.ktrics.ir.Lang
import dev.ktrics.langapi.NodeClassifier
import dev.ktrics.metric.MetricSettings
import dev.ktrics.metric.SkipPolicy
import dev.ktrics.module.ModuleGraph
import dev.ktrics.report.Actionability
import dev.ktrics.report.AnalysisReport
import dev.ktrics.report.FileEntry
import dev.ktrics.report.ReportSummary
import java.io.File

/**
 * Drives one analysis. Builds (or reuses, via [WarmIndexCache]) the single-host session
 * over the module graph, lowers every Kotlin and Java file to IR, builds the module-aware
 * [ProjectIndexImpl], runs the catalogue, and assembles the canonical [AnalysisReport]. Everything
 * happens daemon-side; the AST never crosses the socket. Resolution is name-based until
 * resolution is turned on later via `analyze {}`.
 */
class AnalysisEngine(
    private val projectRoot: File,
    private val settings: MetricSettings = MetricSettings.Default,
    private val skips: SkipPolicy = SkipPolicy.None,
    /** Identity of the effective config; part of the warm-cache key so config changes invalidate it. */
    private val configHash: String = "",
    /** `--strict-dismiss`: ignore all dismissals. */
    private val strictDismiss: Boolean = false,
    /** Include every measurement in the report (regression/snapshot need them). */
    private val includeMeasurements: Boolean = false,
    /** JaCoCo coverage for `complexityJustified` stamping; empty when `--coverage none`. */
    private val coverage: CoverageData = CoverageData.EMPTY,
    /** Resolution turned on: coupling/inheritance edges resolve via the shared symbol space. */
    private val resolved: Boolean = false,
    /** `exclude` globs from ktrics.yaml: matching files are not reported on (still indexed for resolution). */
    private val excludeGlobs: List<String> = emptyList(),
) {
    private val kotlinClassifier: NodeClassifier = KotlinFrontend(projectRoot, resolved).classifier
    private val javaClassifier: NodeClassifier = JavaFrontend(projectRoot, resolved).classifier
    private val excludeMatchers = excludeGlobs.flatMap { glob -> matchersFor(glob) }

    /**
     * Runs serialized under the [WarmIndexCache] monitor: the embedded Analysis API session is shared
     * mutable state and is NOT safe for concurrent `analyze` calls, and holding the monitor across the
     * whole run also prevents a concurrent request from rebuilding the cache and disposing the session
     * this run is still reading (use-after-dispose). The daemon's loop is sequential, so the cost is nil;
     * this can be refined to per-key locking later if true concurrency is ever wanted.
     */
    fun analyze(graph: ModuleGraph): AnalysisReport =
        synchronized(WarmIndexCache) {
            val warm = WarmIndexCache.get(projectRoot, graph, configHash, resolved)
            val classifierFor: (Lang) -> NodeClassifier = { lang ->
                if (lang == Lang.KOTLIN) kotlinClassifier else javaClassifier
            }
            // Excluded files still feed the index (so they remain resolution targets), but are not measured.
            val measuredUnits = warm.units.filterNot { isExcluded(it.path) }
            val output = MetricRunner(warm.index, settings, skips, classifierFor).run(measuredUnits)

            // Apply dismissals: drop accepted ones; keep rejected ones live and flagged.
            val applier = DismissalApplier(projectRoot, Sidecar.load(projectRoot), strictDismiss)
            val (live, _) = applier.partition(output.violations)
            // Stamp complexityJustified from coverage before sorting.
            val annotated = CoverageAnnotator.annotate(live, coverage)
            val violations = Actionability.sort(annotated)
            val files =
                measuredUnits.map { unit ->
                    FileEntry(unit.path, unit.lang, violations.count { it.file == unit.path })
                }.sortedBy { it.path }
            AnalysisReport(
                version = Build.VERSION,
                root = projectRoot.absolutePath,
                summary = ReportSummary.of(violations, files),
                violations = violations,
                files = files,
                measurements = if (includeMeasurements) output.results else emptyList(),
            )
        }

    private fun isExcluded(relativePath: String): Boolean {
        if (excludeMatchers.isEmpty()) return false
        val path = runCatching { java.nio.file.Paths.get(relativePath) }.getOrNull() ?: return false
        return excludeMatchers.any { it.matches(path) }
    }

    /**
     * Path matchers for one configured glob. A leading globstar segment in a Java glob requires a
     * parent path component, so a pattern intended to match `build` anywhere does NOT match a
     * top-level `build/classes/Foo.kt`; we also register the form with that leading globstar segment
     * stripped so both top-level and nested paths match.
     */
    private fun matchersFor(glob: String): List<java.nio.file.PathMatcher> {
        val fs = java.nio.file.FileSystems.getDefault()
        val leadingGlobstar = "**/"
        val variants =
            buildList {
                add(glob)
                if (glob.startsWith(leadingGlobstar)) add(glob.removePrefix(leadingGlobstar))
            }
        return variants.mapNotNull { runCatching { fs.getPathMatcher("glob:$it") }.getOrNull() }
    }

    companion object {
        /**
         * A single-module graph over [path], used until a declared graph is available (ktrics.yaml /
         * --module). Models exactly one module — module *discovery* is v2.
         */
        fun singleModuleGraph(path: File): ModuleGraph = ModuleGraph.singleModule(srcRoots = listOf(path.absolutePath))
    }
}
