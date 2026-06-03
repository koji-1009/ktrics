package dev.ktrics.engine

import dev.ktrics.coverage.CoverageData
import dev.ktrics.metric.Violation

/**
 * Stamps `complexityJustified` on complexity violations whose scope is well-covered. A
 * branch coverage ≥ 0.8 (JaCoCo) means a complex method is thoroughly exercised — surfaced as
 * justified so the loop can accept it rather than refactor reflexively.
 */
object CoverageAnnotator {
    private val COMPLEXITY_METRICS = setOf("cyclomatic-complexity", "cognitive-complexity", "npath-complexity")

    fun annotate(
        violations: List<Violation>,
        coverage: CoverageData,
    ): List<Violation> {
        if (coverage.isEmpty()) return violations
        return violations.map { v ->
            if (v.metricId in COMPLEXITY_METRICS && coverage.complexityJustified(v.scope)) {
                v.copy(complexityJustified = true)
            } else {
                v
            }
        }
    }
}
