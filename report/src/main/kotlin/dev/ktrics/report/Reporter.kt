package dev.ktrics.report

import dev.ktrics.metric.Polarity
import dev.ktrics.metric.Severity
import dev.ktrics.metric.Violation

/** A reporter renders an [AnalysisReport] to text. */
interface Reporter {
    fun render(report: AnalysisReport): String
}

/** The five output formats. */
enum class ReporterFormat(val id: String) {
    CONSOLE("console"),
    JSON("json"),
    MD("md"),
    AI("ai"),
    SARIF("sarif"),
    ;

    companion object {
        fun fromId(id: String): ReporterFormat? = entries.firstOrNull { it.id == id.lowercase() }

        val ids: List<String> get() = entries.map { it.id }
    }
}

/** Resolves a [Reporter] for a format. The AI reporter is the primary integration surface. */
object Reporters {
    fun forFormat(
        format: ReporterFormat,
        sources: SourceProvider = SourceProvider.None,
        autoExplain: Boolean = true,
        /** `--limit`: caps violations + unused + signals in the ai reporter (after the priority sort). */
        limit: Int? = null,
    ): Reporter =
        when (format) {
            ReporterFormat.CONSOLE -> ConsoleReporter()
            ReporterFormat.JSON -> JsonReporter()
            ReporterFormat.MD -> MarkdownReporter()
            ReporterFormat.AI -> AiReporter(sources, autoExplain, limit)
            ReporterFormat.SARIF -> SarifReporter()
        }
}

/**
 * Sort by actionability: errors before warnings, then by how far over threshold the value
 * sits (a 3× breach outranks a 1.1× breach), then by file/line for run-to-run stability.
 */
object Actionability {
    fun sort(violations: List<Violation>): List<Violation> =
        violations.sortedWith(
            compareByDescending<Violation> { it.severity == Severity.ERROR }
                .thenByDescending { breachRatio(it) }
                .thenBy { it.file }
                .thenBy { it.span.startLine }
                .thenBy { it.metricId },
        )

    /** How far past the threshold, normalised so higher = more actionable regardless of polarity. */
    private fun breachRatio(v: Violation): Double {
        if (v.threshold == 0.0) {
            // Ratio is undefined at threshold 0; map each polarity to the same ordering the
            // non-zero branch produces (more-severe → larger), avoiding the divide-by-zero.
            return when (v.polarity) {
                // Any positive value breaches a 0 limit; severity grows with the value.
                Polarity.LOWER_IS_BETTER -> v.value
                // value <= 0 is the worst possible reading; larger value is less severe.
                Polarity.HIGHER_IS_BETTER -> if (v.value <= 0.0) Double.MAX_VALUE else 1.0 / v.value
                Polarity.INFORMATIONAL -> 0.0
            }
        }
        return when (v.polarity) {
            Polarity.LOWER_IS_BETTER -> v.value / v.threshold
            Polarity.HIGHER_IS_BETTER -> if (v.value == 0.0) Double.MAX_VALUE else v.threshold / v.value
            Polarity.INFORMATIONAL -> 0.0
        }
    }
}
