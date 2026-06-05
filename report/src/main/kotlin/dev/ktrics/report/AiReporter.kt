package dev.ktrics.report

import dev.ktrics.ir.CallGraphSignal
import dev.ktrics.ir.StaleDismissal
import dev.ktrics.ir.UnusedEntry
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
            // Cap every section up front so `counts:` is written from the SAME kept lists the
            // sections then render — derived twice, the numbers could drift.
            val (violations, violationsDropped) = cap(Actionability.sort(report.violations))
            val (unused, unusedDropped) = cap(report.unused)
            val (stale, staleDropped) = cap(report.staleDismissals)
            val (signals, signalsDropped) = cap(sortBySignalConnectivity(report.signals))
            appendHeader(report)
            appendCounts(violations.size, unused.size, stale.size, signals.size)
            appendViolations(violations)
            appendUnused(unused)
            appendSignals(signals)
            appendStaleDismissals(stale)
            appendTruncated(violationsDropped, unusedDropped, staleDropped, signalsDropped)
        }

    /** Applies the per-section [limit] cap; returns (kept, dropped count). */
    private fun <T> cap(items: List<T>): Pair<List<T>, Int> {
        val kept = limit?.let { items.take(it) } ?: items
        return kept to (items.size - kept.size)
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

    /**
     * Per-section totals in ONE place (all four sections share the `  - file:` entry shape, so an
     * agent that greps to count findings over-counts — the dartrics 1.1.0 fix).
     */
    private fun StringBuilder.appendCounts(
        violations: Int,
        unused: Int,
        staleDismissals: Int,
        signals: Int,
    ) {
        appendLine("# Entries included per section. With --limit, dropped tails")
        appendLine("# land in a trailing truncated block (total = counts + drops).")
        appendLine("counts:")
        appendLine("  violations: $violations")
        appendLine("  unused: $unused")
        appendLine("  staleDismissals: $staleDismissals")
        appendLine("  signals: $signals")
    }

    /** Emits the violations block (sorted by actionability, capped by [limit]). */
    private fun StringBuilder.appendViolations(kept: List<Violation>) {
        if (kept.isEmpty()) {
            appendLine("violations: []")
        } else {
            appendLine("violations:")
            for (v in kept) appendViolation(v)
        }
    }

    private fun StringBuilder.appendTruncated(
        violations: Int,
        unused: Int,
        staleDismissals: Int,
        signals: Int,
    ) {
        val drops =
            listOf(
                "violations" to violations,
                "unused" to unused,
                "staleDismissals" to staleDismissals,
                "signals" to signals,
            ).filter { it.second > 0 }
        if (drops.isEmpty()) return
        appendLine("truncated:")
        drops.forEach { (section, dropped) -> appendLine("  $section: $dropped") }
    }

    /**
     * Unreachable public declarations (reference, not a verdict): a 0-reachability reading can be
     * genuine dead code OR an unwired implementation.
     */
    private fun StringBuilder.appendUnused(kept: List<UnusedEntry>) {
        if (kept.isEmpty()) return
        appendLine("# Unused entries may be leftover code to delete OR unwired implementations")
        appendLine("# (declared but never reached — a possible wiring gap). Confirm against intent first.")
        appendLine("unused:")
        for (u in kept) {
            appendLine("  - file: ${u.file}")
            appendLine("    line: ${u.line}")
            appendLine("    kind: ${u.kind}")
            appendLine("    name: ${u.name}")
            appendSnippet(u.file, u.line, indent = "    ")
        }
    }

    /** Dismissal directives whose violation no longer fires — dead suppressions to remove. */
    private fun StringBuilder.appendStaleDismissals(kept: List<StaleDismissal>) {
        if (kept.isEmpty()) return
        appendLine("# Dismissals whose violation no longer fires — the suppression is dead; remove the directive.")
        appendLine("staleDismissals:")
        for (s in kept) {
            appendLine("  - source: ${s.source}")
            s.file?.let { appendLine("    file: $it") }
            s.line?.let { appendLine("    line: $it") }
            s.metric?.let { appendLine("    metric: $it") }
            s.scope?.let { appendLine("    scope: $it") }
            s.id?.let { appendLine("    id: $it") }
            appendLine("    reason: ${quote(s.reason)}")
        }
    }

    private fun sortBySignalConnectivity(signals: List<CallGraphSignal>): List<CallGraphSignal> =
        signals.sortedWith(compareByDescending<CallGraphSignal> { it.fanInCallers }.thenByDescending { it.fanOutCallees })

    /**
     * Per-declaration fan-in/fan-out from the resolved call graph — reference values, NOT findings.
     * Sorted so the most-connected scopes lead and the `0/0` tail is what [limit] truncates.
     */
    private fun StringBuilder.appendSignals(kept: List<CallGraphSignal>) {
        if (kept.isEmpty()) return
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
        appendSnippet(v.file, v.span.startLine, indent = "    ")
    }

    private fun StringBuilder.appendDismissal(v: Violation) {
        when (val d = v.dismissal) {
            is dev.ktrics.metric.DismissalState.Rejected ->
                appendLine("    dismissalRejected: ${quote("reason too short (< ${d.minReasonLength} chars): ${d.reason}")}")
            else -> {}
        }
    }

    private fun StringBuilder.appendSnippet(
        file: String,
        targetLine: Int,
        indent: String,
    ) {
        val (firstLine, lines) = sources.lines(file, targetLine - SNIPPET_RADIUS, targetLine + SNIPPET_RADIUS)
        if (lines.isEmpty()) return
        appendLine("${indent}snippet: |")
        lines.forEachIndexed { i, line ->
            val lineNo = firstLine + i
            val marker = if (lineNo == targetLine) ">" else " "
            appendLine("$indent  $marker ${lineNo.toString().padStart(4)}| $line")
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
