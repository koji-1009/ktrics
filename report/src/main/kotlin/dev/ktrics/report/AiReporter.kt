package dev.ktrics.report

import dev.ktrics.metric.Violation

/**
 * The `ai` reporter — the primary integration surface. Header `# ktrics ai-report v1`
 * is a contractual constant; field names are stable through 0.x. YAML-ish, sorted by actionability.
 *
 * Every fired metric carries auto-explain inline (rationale, refactorHints, references, polarity) so
 * an agent never needs a second call to learn why. Per violation it also stamps
 * `language: java|kotlin` (one run spans both) and `resolution: resolved|name-based` so the agent
 * knows the confidence of coupling metrics. Snippet = `line ± 3`.
 */
class AiReporter(
    private val sources: SourceProvider = SourceProvider.None,
    /** `--no-auto-explain` sets this false to omit rationale/hints/references. */
    private val autoExplain: Boolean = true,
    /** `--limit`: caps violations + unused + signals after the priority sort; null keeps everything. */
    private val limit: Int? = null,
) : Reporter {
    override fun render(report: AnalysisReport): String =
        buildString {
            appendHeader(report)
            val violationsDropped = appendViolations(report)
            val unusedDropped = appendUnused(report)
            val signalsDropped = appendSignals(report)
            appendTruncated(violationsDropped, unusedDropped, signalsDropped)
        }

    private fun StringBuilder.appendHeader(report: AnalysisReport) {
        appendLine(AnalysisReport.AI_HEADER)
        appendLine("tool: ${report.tool} ${report.version}")
        appendLine("root: ${report.root}")
        appendLine("summary:")
        with(report.summary) {
            appendLine("  files: $filesAnalyzed (java: $javaFiles, kotlin: $kotlinFiles)")
            appendLine("  violations: $violations (errors: $errors, warnings: $warnings)")
            appendLine("  name-based-results: $nameBasedResults")
        }
    }

    /** Emits the violations block (sorted by actionability, capped by [limit]); returns the count dropped. */
    private fun StringBuilder.appendViolations(report: AnalysisReport): Int {
        val sorted = Actionability.sort(report.violations)
        val kept = limit?.let { sorted.take(it) } ?: sorted
        if (kept.isEmpty()) {
            appendLine("violations: []")
        } else {
            appendLine("violations:")
            for (v in kept) appendViolation(v)
        }
        return sorted.size - kept.size
    }

    private fun StringBuilder.appendTruncated(
        violations: Int,
        unused: Int,
        signals: Int,
    ) {
        if (violations <= 0 && unused <= 0 && signals <= 0) return
        appendLine("truncated:")
        if (violations > 0) appendLine("  violations: $violations")
        if (unused > 0) appendLine("  unused: $unused")
        if (signals > 0) appendLine("  signals: $signals")
    }

    /**
     * Unreachable public declarations (reference, not a verdict): a 0-reachability reading can be
     * genuine dead code OR an unwired implementation. Returns the count dropped to honour [limit].
     */
    private fun StringBuilder.appendUnused(report: AnalysisReport): Int {
        if (report.unused.isEmpty()) return 0
        val kept = limit?.let { report.unused.take(it) } ?: report.unused
        appendLine("# Unused entries may be leftover code to delete OR unwired implementations")
        appendLine("# (declared but never reached — a possible wiring gap). Confirm against intent first.")
        appendLine("unused:")
        for (u in kept) {
            appendLine("  - file: ${u.file}")
            appendLine("    line: ${u.line}")
            appendLine("    kind: ${u.kind}")
            appendLine("    name: ${u.name}")
        }
        return report.unused.size - kept.size
    }

    /**
     * Per-declaration fan-in/fan-out from the resolved call graph — reference values, NOT findings.
     * Sorted so the most-connected scopes lead and the `0/0` tail is what [limit] truncates. Returns
     * the count dropped.
     */
    private fun StringBuilder.appendSignals(report: AnalysisReport): Int {
        if (report.signals.isEmpty()) return 0
        val sorted =
            report.signals.sortedWith(
                compareByDescending<dev.ktrics.ir.CallGraphSignal> { it.fanInCallers }.thenByDescending { it.fanOutCallees },
            )
        val kept = limit?.let { sorted.take(it) } ?: sorted
        appendLine("# Reference values from the call graph — compare against intent, NOT verdicts.")
        appendLine("# A high fan-in is not \"bad\"; a 0 fan-in on a public API is a possible wiring gap.")
        appendLine("signals:")
        for (s in kept) {
            appendLine("  - file: ${s.file}")
            appendLine("    line: ${s.line}")
            appendLine("    scope: ${s.scopeName}")
            appendLine("    kind: ${s.kind}")
            appendLine("    fanInCallers: ${s.fanInCallers}")
            appendLine("    fanInCalls: ${s.fanInCalls}")
            appendLine("    fanOutCallees: ${s.fanOutCallees}")
            appendLine("    fanOutCalls: ${s.fanOutCalls}")
        }
        return sorted.size - kept.size
    }

    private fun StringBuilder.appendViolation(v: Violation) {
        appendLine("  - id: ${v.id}")
        appendLine("    metric: ${v.metricId}")
        appendLine("    severity: ${v.severity.wireName}")
        appendLine("    language: ${v.lang.id}")
        v.resolution?.let { appendLine("    resolution: ${it.wireName}") }
        appendLine("    polarity: ${v.polarity.wireName}")
        appendLine("    scope: ${v.scope}")
        appendLine("    location: ${v.file}:${v.span.startLine}")
        appendLine("    value: ${formatNumber(v.value)}")
        appendLine("    threshold: ${formatNumber(v.threshold)}")
        if (v.complexityJustified) appendLine("    complexityJustified: true")
        appendDismissal(v)
        if (autoExplain) {
            appendLine("    rationale: ${quote(v.rationale)}")
            if (v.refactorHints.isNotEmpty()) {
                appendLine("    refactorHints:")
                v.refactorHints.forEach { appendLine("      - ${quote(it)}") }
            }
            if (v.references.isNotEmpty()) {
                appendLine("    references:")
                v.references.forEach { appendLine("      - ${quote(it)}") }
            }
        }
        appendSnippet(v)
    }

    private fun StringBuilder.appendDismissal(v: Violation) {
        when (val d = v.dismissal) {
            is dev.ktrics.metric.DismissalState.Rejected ->
                appendLine("    dismissalRejected: ${quote("reason too short (< ${d.minReasonLength} chars): ${d.reason}")}")
            else -> {}
        }
    }

    private fun StringBuilder.appendSnippet(v: Violation) {
        val (firstLine, lines) = sources.lines(v.file, v.span.startLine - SNIPPET_RADIUS, v.span.startLine + SNIPPET_RADIUS)
        if (lines.isEmpty()) return
        appendLine("    snippet: |")
        lines.forEachIndexed { i, line ->
            val lineNo = firstLine + i
            val marker = if (lineNo == v.span.startLine) ">" else " "
            appendLine("      $marker ${lineNo.toString().padStart(4)}| $line")
        }
    }

    /**
     * A YAML double-quoted scalar. Escapes `\` and `"`, plus newlines/tabs/control characters — a raw
     * newline in a double-quoted scalar (e.g. a multi-line dismissal reason) would otherwise produce
     * malformed YAML and break a consuming parser.
     */
    private fun quote(s: String): String =
        buildString {
            append('"')
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format(java.util.Locale.ROOT, "\\x%02x", c.code)) else append(c)
            }
            append('"')
        }

    private fun formatNumber(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", d)

    private companion object {
        const val SNIPPET_RADIUS = 3
    }
}
