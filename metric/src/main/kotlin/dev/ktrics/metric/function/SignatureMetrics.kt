package dev.ktrics.metric.function

import dev.ktrics.ir.FunctionDecl
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.FunctionMetric
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Thresholds

/**
 * Parameter count. Kotlin discounts parameters with default values — named-arg call sites blunt the
 * "too many positional args" trap. The discount is driven generically off [param.hasDefault]
 * (always false on Java), so the calculator stays language-free.
 */
class NumberOfParameters : FunctionMetric {
    override val def =
        MetricDef(
            id = "number-of-parameters",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "—",
            rationale =
                "Long parameter lists are hard to call correctly and often signal a missing value " +
                    "object. Kotlin defaulted parameters are discounted because named arguments mitigate the trap.",
            refactorHints =
                listOf(
                    "Introduce a parameter object / data class grouping related arguments.",
                    "Use a builder for optional configuration (Java).",
                    "Give Kotlin parameters sensible default values and call by name.",
                ),
            references = listOf("Fowler, M. Refactoring — Long Parameter List."),
            defaults = Thresholds.warnError(warn = 4.0, err = 8.0),
            // Kotlin tolerates more because named/defaulted args reduce the call-site risk.
            perLanguage = mapOf("kotlin" to Thresholds(warning = 6.0)),
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double = fn.params.count { !it.hasDefault }.toDouble()
}

/**
 * Boolean-trap (McConnell 2004; Bloch 2018): the count of boolean parameters. A `foo(true, false)`
 * call site is unreadable; each boolean flag usually hides two methods or an enum.
 */
class BooleanTrap : FunctionMetric {
    override val def =
        MetricDef(
            id = "boolean-trap",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "McConnell 2004; Bloch 2018",
            rationale =
                "Boolean parameters make call sites opaque (`render(true, false)`) and usually encode " +
                    "a hidden mode. They multiply behaviours a method must support.",
            refactorHints =
                listOf(
                    "Replace the boolean with a two-value enum that names each case.",
                    "Split the method into two intention-revealing methods.",
                ),
            references =
                listOf(
                    "McConnell, S. (2004). Code Complete, 2nd ed.",
                    "Bloch, J. (2018). Effective Java, 3rd ed., Item 51.",
                ),
            defaults = Thresholds.warn(2.0),
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double = fn.params.count { it.isBoolean }.toDouble()
}
