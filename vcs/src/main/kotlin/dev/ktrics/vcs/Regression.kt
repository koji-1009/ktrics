package dev.ktrics.vcs

import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.metric.MetricResult
import dev.ktrics.metric.Polarity
import kotlinx.serialization.Serializable

/** How a single (scope, metric) measurement moved between two refs. */
@Serializable
enum class Change { IMPROVED, REGRESSED, UNCHANGED, ADDED, REMOVED }

@Serializable
data class RegressionEntry(
    val file: String,
    val scope: String,
    val metric: String,
    val before: Double?,
    val after: Double?,
    val change: Change,
) {
    val delta: Double? get() = if (before != null && after != null) after - before else null
}

@Serializable
data class RegressionReport(
    val entries: List<RegressionEntry>,
    val improved: Int,
    val regressed: Int,
    val unchanged: Int,
    val added: Int,
    val removed: Int,
    /** The cosmetic-refactor heuristic fired: churn that shuffles code without reducing complexity. */
    val cosmeticRefactorSuspected: Boolean,
)

/**
 * Diffs two measurement sets per-scope per-metric, classified by polarity. Ports the
 * sibling cosmetic-refactor heuristic: `tinyHelpersAdded ≥ 3 AND slocDelta > 4·helpers AND
 * ccReduction < 2·helpers` — many tiny helpers added, lines up, complexity barely down.
 */
object Regression {
    fun compare(
        before: List<MetricResult>,
        after: List<MetricResult>,
    ): RegressionReport {
        val beforeByKey = before.associateBy { key(it) }
        val afterByKey = after.associateBy { key(it) }
        val keys = (beforeByKey.keys + afterByKey.keys)

        val entries =
            keys.map { k ->
                val b = beforeByKey[k]
                val a = afterByKey[k]
                val change = classify(b?.value, a?.value, polarity(k.third))
                // The key's second component is `scopeName` (param-qualified, to keep overloads distinct);
                // surface the dotted `scope` for display, taking it from whichever side is present.
                val scope = (b ?: a)?.scope ?: k.second
                RegressionEntry(k.first, scope, k.third, b?.value, a?.value, change)
            }.sortedWith(compareBy({ it.file }, { it.scope }, { it.metric }))

        return RegressionReport(
            entries = entries,
            improved = entries.count { it.change == Change.IMPROVED },
            regressed = entries.count { it.change == Change.REGRESSED },
            unchanged = entries.count { it.change == Change.UNCHANGED },
            added = entries.count { it.change == Change.ADDED },
            removed = entries.count { it.change == Change.REMOVED },
            cosmeticRefactorSuspected = detectCosmeticRefactor(beforeByKey, afterByKey),
        )
    }

    private fun classify(
        before: Double?,
        after: Double?,
        polarity: Polarity,
    ): Change =
        when {
            before == null && after != null -> Change.ADDED
            before != null && after == null -> Change.REMOVED
            before == after -> Change.UNCHANGED
            before == null || after == null -> Change.UNCHANGED
            polarity == Polarity.INFORMATIONAL -> Change.UNCHANGED
            polarity == Polarity.LOWER_IS_BETTER -> if (after < before) Change.IMPROVED else Change.REGRESSED
            else -> if (after > before) Change.IMPROVED else Change.REGRESSED // HIGHER_IS_BETTER
        }

    /** tinyHelpersAdded ≥ 3 AND slocDelta > 4·helpers AND ccReduction < 2·helpers. */
    private fun detectCosmeticRefactor(
        before: Map<Triple<String, String, String>, MetricResult>,
        after: Map<Triple<String, String, String>, MetricResult>,
    ): Boolean {
        val ccMetric = "cyclomatic-complexity"
        val slocMetric = "source-lines-of-code"

        val newCcScopes = after.filter { (k, _) -> k.third == ccMetric && k !in before }
        val tinyHelpers = newCcScopes.values.count { it.value <= TINY_CC }
        if (tinyHelpers < MIN_HELPERS) return false

        val slocDelta = totalDelta(before, after, slocMetric)
        val ccReduction =
            before.entries.filter { it.key.third == ccMetric && it.key in after }
                .sumOf { (k, b) -> (b.value - after.getValue(k).value).coerceAtLeast(0.0) }

        return slocDelta > 4 * tinyHelpers && ccReduction < 2 * tinyHelpers
    }

    private fun totalDelta(
        before: Map<Triple<String, String, String>, MetricResult>,
        after: Map<Triple<String, String, String>, MetricResult>,
        metric: String,
    ): Double {
        val afterSum = after.filter { it.key.third == metric }.values.sumOf { it.value }
        val beforeSum = before.filter { it.key.third == metric }.values.sumOf { it.value }
        return afterSum - beforeSum
    }

    private fun polarity(metric: String): Polarity = BuiltinMetrics.def(metric)?.polarity ?: Polarity.LOWER_IS_BETTER

    // Match by `scopeName` (param-qualified, e.g. `Foo.bar(int)`) rather than `scope`: the dotted scope
    // omits the parameter signature, so overloads `C.foo(int)`/`C.foo(String)` would collide on one key and
    // `associateBy` would silently drop a regression in one of them. `scopeName` is built deterministically
    // from the declaration's signature, so the same overload still matches across before/after.
    private fun key(r: MetricResult): Triple<String, String, String> = Triple(r.file, r.scopeName, r.metricId)

    private const val TINY_CC = 2.0
    private const val MIN_HELPERS = 3
}
