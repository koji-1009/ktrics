package dev.ktrics.metric.function

import dev.ktrics.ir.FunctionDecl
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.FunctionMetric
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Thresholds
import dev.ktrics.metric.text.SourceLines

/** Boehm 1981 SLOC: non-blank, non-comment-only lines of the method body. */
class SourceLinesOfCode : FunctionMetric {
    override val def =
        MetricDef(
            id = "source-lines-of-code",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "Boehm 1981",
            rationale =
                "Effective lines of code in the method body (blanks and comments excluded). A long " +
                    "method is a coarse but reliable smell that it carries more than one responsibility.",
            refactorHints =
                listOf(
                    "Extract cohesive sections into named helper methods.",
                    "Move incidental setup into factories or builders.",
                ),
            references = listOf("Boehm, B. (1981). Software Engineering Economics."),
            defaults = Thresholds.warn(60.0),
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double? {
        val body = fn.bodyNode ?: return null // abstract/interface methods have no body to count
        return SourceLines.count(ctx.classifier.text(body)).toDouble()
    }
}

/**
 * Raw body span length ("method-length", body span). No default threshold — it only
 * measures, feeding regression context and the `ai` snippet. Distinct from SLOC: this counts every
 * physical line including blanks/comments, so the two together reveal comment-heavy vs dense bodies.
 */
class MethodLength : FunctionMetric {
    override val def =
        MetricDef(
            id = "method-length",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "—",
            rationale =
                "Physical line span of the method body. Measured for context and regression; not " +
                    "gated by default — pair it with source-lines-of-code to judge density.",
            refactorHints = listOf("Extract a section into a helper if the span is dominated by one concern."),
            references = emptyList(),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double? {
        val body = fn.bodyNode ?: return null
        return (ctx.classifier.text(body).count { it == '\n' } + 1).toDouble()
    }
}
