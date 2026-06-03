package dev.ktrics.report

import dev.ktrics.metric.Violation

/** Markdown output: a summary plus a violations table, suitable for PR comments. */
class MarkdownReporter : Reporter {
    override fun render(report: AnalysisReport): String =
        buildString {
            appendLine("# ktrics report")
            appendLine()
            appendLine("`${report.tool} ${report.version}` — root `${report.root}`")
            appendLine()
            with(report.summary) {
                appendLine("| files | java | kotlin | violations | errors | warnings |")
                appendLine("| ---: | ---: | ---: | ---: | ---: | ---: |")
                appendLine("| $filesAnalyzed | $javaFiles | $kotlinFiles | $violations | $errors | $warnings |")
            }
            appendLine()
            if (report.violations.isEmpty()) {
                appendLine("✓ No violations.")
                return@buildString
            }
            appendLine("## Violations")
            appendLine()
            appendLine("| sev | metric | scope | value | limit | loc | lang | res | id |")
            appendLine("| --- | --- | --- | ---: | ---: | --- | --- | --- | --- |")
            Actionability.sort(report.violations).forEach { v -> appendRow(v) }
        }

    private fun StringBuilder.appendRow(v: Violation) {
        val loc = "${v.file}:${v.span.startLine}"
        val res = v.resolution?.wireName ?: "—"
        appendLine(
            "| ${v.severity.wireName} | `${escape(v.metricId)}` | `${escape(v.scopeName)}` | " +
                "${formatNumber(v.value)} | ${formatNumber(v.threshold)} | `${escape(loc)}` | ${v.lang.id} | $res | `${v.id}` |",
        )
    }

    /**
     * Neutralizes characters that would break a table cell or a backtick-wrapped code span. A
     * backtick cannot be escaped inside a code span, so it is swapped for a visually-similar prime
     * (`′`); newlines/carriage-returns collapse to a space; backslashes and pipes are kept literal
     * (`\\`/`\|`) so they render as written rather than corrupting the row.
     */
    private fun escape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("`", "′")
            .replace("|", "\\|")
            .replace("\r", " ")
            .replace("\n", " ")

    private fun formatNumber(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", d)
}
