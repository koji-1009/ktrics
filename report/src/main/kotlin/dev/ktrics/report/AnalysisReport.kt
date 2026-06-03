package dev.ktrics.report

import dev.ktrics.ir.CallGraphSignal
import dev.ktrics.ir.Lang
import dev.ktrics.ir.UnusedEntry
import dev.ktrics.metric.MetricResult
import dev.ktrics.metric.Severity
import dev.ktrics.metric.Violation
import kotlinx.serialization.Serializable

/**
 * The full result of an `analyze` run — the canonical model every reporter renders, and the exact
 * shape persisted by the json reporter and re-read by `ktrics report`. Field names are
 * stable through 0.x.
 */
@Serializable
data class AnalysisReport(
    val tool: String = "ktrics",
    val version: String,
    /** Project-relative root the analysis ran against. */
    val root: String,
    val summary: ReportSummary,
    /** Live violations (dismissed ones excluded; rejected-dismissal ones included), sorted by actionability. */
    val violations: List<Violation>,
    /** Per-file language tally and counts, for the console header and md tables. */
    val files: List<FileEntry> = emptyList(),
    /**
     * Every measurement (not just breaches), populated only when requested — `regression`/`snapshot`
     * diff these by polarity. Empty for normal `analyze` runs so the ai/json output stays lean.
     */
    val measurements: List<MetricResult> = emptyList(),
    /** Unreachable public-API declarations (the `ai` report's `unused:` block); reference, not a gate. */
    val unused: List<UnusedEntry> = emptyList(),
    /** Reference-only call-graph fan-in/fan-out for the scopes that fired (the `signals:` block). */
    val signals: List<CallGraphSignal> = emptyList(),
) {
    companion object {
        const val AI_HEADER: String = "# ktrics ai-report v1"
    }
}

@Serializable
data class ReportSummary(
    val filesAnalyzed: Int,
    val javaFiles: Int,
    val kotlinFiles: Int,
    val violations: Int,
    val errors: Int,
    val warnings: Int,
    /** Number of coupling/cohesion results that fell back to name-based resolution. */
    val nameBasedResults: Int = 0,
) {
    companion object {
        fun of(
            violations: List<Violation>,
            files: List<FileEntry>,
        ): ReportSummary =
            ReportSummary(
                filesAnalyzed = files.size,
                javaFiles = files.count { it.lang == Lang.JAVA },
                kotlinFiles = files.count { it.lang == Lang.KOTLIN },
                violations = violations.size,
                errors = violations.count { it.severity == Severity.ERROR },
                warnings = violations.count { it.severity == Severity.WARNING },
                nameBasedResults = violations.count { it.resolution == dev.ktrics.ir.Resolution.NAME_BASED },
            )
    }
}

@Serializable
data class FileEntry(
    val path: String,
    val lang: Lang,
    val violations: Int,
)
