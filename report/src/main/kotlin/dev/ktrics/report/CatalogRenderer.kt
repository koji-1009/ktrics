package dev.ktrics.report

import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Thresholds

/**
 * Renders the metric catalogue for `ktrics rules` and the per-metric auto-explain for `ktrics explain`.
 * Auto-explain states `appliesTo` and the structural reason a metric does not fire in a
 * language, so absence is never read as a bug.
 */
object CatalogRenderer {
    fun rules(): String =
        buildString {
            appendLine("ktrics metric catalogue (${BuiltinMetrics.all.size} metrics)")
            ScopeKind.entries.forEach { scope ->
                val metrics = BuiltinMetrics.all.filter { it.scopeKind == scope }
                if (metrics.isEmpty()) return@forEach
                appendLine()
                appendLine("== ${scope.name.lowercase()} level ==")
                metrics.forEach { appendLine(oneLine(it)) }
            }
            appendLine()
            appendLine("Run `ktrics explain <metric-id>` for full rationale, hints and references.")
        }

    private fun oneLine(def: MetricDef): String {
        val state = if (def.enabledByDefault) "" else " (off by default)"
        val thr = thresholdLabel(def.defaults)
        return "  ${def.id.padEnd(32)} ${def.appliesTo.wireName.padEnd(6)} ${def.polarity.wireName.padEnd(14)} $thr$state"
    }

    /** Full auto-explain for one metric id (used by `explain <metric-id>`). */
    fun explainMetric(id: String): String? {
        val def = BuiltinMetrics.def(id) ?: return null
        return buildString {
            appendLine("metric: ${def.id}")
            appendLine("scope: ${def.scopeKind.name.lowercase()}")
            appendLine("appliesTo: ${def.appliesTo.wireName}")
            appendLine("polarity: ${def.polarity.wireName}")
            appendLine("source: ${def.source}")
            appendLine("defaultThresholds: ${thresholdLabel(def.defaults)}")
            if (def.perLanguage.isNotEmpty()) {
                appendLine("perLanguage:")
                def.perLanguage.forEach { (lang, t) -> appendLine("  $lang: ${thresholdLabel(t)}") }
            }
            appendLine("enabledByDefault: ${def.enabledByDefault}")
            appendLine("rationale: ${def.rationale}")
            if (def.refactorHints.isNotEmpty()) {
                appendLine("refactorHints:")
                def.refactorHints.forEach { appendLine("  - $it") }
            }
            if (def.references.isNotEmpty()) {
                appendLine("references:")
                def.references.forEach { appendLine("  - $it") }
            }
            def.structuralReason?.let {
                appendLine("structuralReason (why it does not fire in the other language): $it")
            }
        }
    }

    private fun thresholdLabel(t: Thresholds): String {
        val parts =
            listOfNotNull(
                t.warning?.let { "warn ${num(it)}" },
                t.error?.let { "err ${num(it)}" },
            )
        return parts.joinToString(" / ").ifEmpty { "measure-only" }
    }

    private fun num(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
