package dev.ktrics.report

import dev.ktrics.metric.Violation

/** Markdown output: summary, violations, unused, signals, and stale-dismissal tables for PR comments. */
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
            } else {
                appendLine("## Violations")
                appendLine()
                appendLine("| sev | metric | scope | value | limit | loc | lang | res | id |")
                appendLine("| --- | --- | --- | ---: | ---: | --- | --- | --- | --- |")
                Actionability.sort(report.violations).forEach { v -> appendRow(v) }
            }
            appendUnused(report)
            appendSignals(report)
            appendStaleDismissals(report)
        }

    private fun StringBuilder.appendRow(v: Violation) {
        val loc = "${v.file}:${v.span.startLine}"
        val res = v.resolution?.wireName ?: "—"
        appendLine(
            "| ${v.severity.wireName} | `${escape(v.metricId)}` | `${escape(v.scopeName)}` | " +
                "${formatNumber(v.value)} | ${formatNumber(v.threshold)} | `${escape(loc)}` | ${v.lang.id} | $res | `${v.id}` |",
        )
    }

    private fun StringBuilder.appendUnused(report: AnalysisReport) {
        if (report.unused.isEmpty()) return
        appendLine()
        appendLine("## Unused (reference)")
        appendLine()
        appendLine("> Leftover code to delete OR unwired implementations — confirm against intent first.")
        appendLine()
        appendLine("| kind | name | loc |")
        appendLine("| --- | --- | --- |")
        report.unused.forEach { u ->
            appendLine("| ${escape(u.kind)} | `${escape(u.name)}` | `${escape("${u.file}:${u.line}")}` |")
        }
    }

    private fun StringBuilder.appendSignals(report: AnalysisReport) {
        if (report.signals.isEmpty()) return
        appendLine()
        appendLine("## Signals (reference)")
        appendLine()
        appendLine("> Call-graph fan-in/fan-out — reference values to compare against intent, not verdicts.")
        appendLine()
        appendLine("| scope | kind | fanInCallers | fanInCalls | fanOutCallees | fanOutCalls | loc |")
        appendLine("| --- | --- | ---: | ---: | ---: | ---: | --- |")
        report.signals.forEach { s ->
            appendLine(
                "| `${escape(s.scopeName)}` | ${escape(s.kind)} | ${s.fanInCallers} | ${s.fanInCalls} | " +
                    "${s.fanOutCallees} | ${s.fanOutCalls} | `${escape("${s.file}:${s.line}")}` |",
            )
        }
    }

    private fun StringBuilder.appendStaleDismissals(report: AnalysisReport) {
        if (report.staleDismissals.isEmpty()) return
        appendLine()
        appendLine("## Stale Dismissals")
        appendLine()
        appendLine("> The violations these directives suppressed no longer fire — remove the directives.")
        appendLine()
        appendLine("| source | where | metric | reason |")
        appendLine("| --- | --- | --- | --- |")
        report.staleDismissals.forEach { s ->
            appendLine(
                "| ${escape(s.source)} | `${escape(s.where())}` | ${s.metric?.let { "`${escape(it)}`" } ?: "—"} | ${escape(s.reason)} |",
            )
        }
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
