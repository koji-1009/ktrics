package dev.ktrics.report

import dev.ktrics.metric.Polarity
import dev.ktrics.metric.Severity
import dev.ktrics.metric.Violation

/**
 * Human-readable console output. Summary line, then violations grouped by file in
 * actionability order. Mirrors the sibling tools' terse, scannable style. Unlike the `ai` reporter it
 * does not slice source snippets, so it takes no [SourceProvider].
 */
class ConsoleReporter : Reporter {
    override fun render(report: AnalysisReport): String =
        buildString {
            val s = report.summary
            appendLine("ktrics ${report.version} — ${s.filesAnalyzed} files (java ${s.javaFiles}, kotlin ${s.kotlinFiles})")
            if (report.violations.isEmpty()) {
                appendLine("✓ no violations")
                return@buildString
            }
            Actionability.sort(report.violations)
                .groupBy { it.file }
                .toSortedMap()
                .forEach { (file, violations) ->
                    appendLine()
                    appendLine(file)
                    violations.forEach { appendViolation(it) }
                }
            appendLine()
            appendLine("${s.violations} violation(s): ${s.errors} error(s), ${s.warnings} warning(s)")
            if (s.nameBasedResults > 0) {
                appendLine("note: ${s.nameBasedResults} coupling result(s) were name-based (classpath incomplete).")
            }
        }

    private fun StringBuilder.appendViolation(v: Violation) {
        val tag = if (v.severity == Severity.ERROR) "error" else "warn "
        val res = v.resolution?.let { " [${it.wireName}]" } ?: ""
        // Operator by polarity: a HIGHER_IS_BETTER metric (e.g. maintainability-index) breaches when the
        // value is at/below its floor, so a hardcoded `>` printed a false comparison like `15 > 65`.
        val op = if (v.polarity == Polarity.HIGHER_IS_BETTER) "<" else ">"
        appendLine(
            "  $tag ${v.span.startLine}:${v.span.startColumn}  ${v.metricId}  " +
                "${formatNumber(v.value)} $op ${formatNumber(v.threshold)}  ${v.scopeName}$res  (${v.id})",
        )
    }

    private fun formatNumber(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", d)
}
