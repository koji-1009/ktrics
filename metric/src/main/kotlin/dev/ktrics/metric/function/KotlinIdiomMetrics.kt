package dev.ktrics.metric.function

import com.intellij.psi.PsiElement
import dev.ktrics.ir.FunctionDecl
import dev.ktrics.langapi.NodeClassifier
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.FunctionMetric
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Thresholds

/**
 * Kotlin's idiom lens — the `panic-density` analog. Counts `!!` not-null assertions in
 * the body; each is a place the type system was overruled and an NPE can surface. Java has no analog,
 * so the metric is scoped `kotlin` and never asks the Java classifier (auto-explain states the reason).
 */
class NotNullAssertionDensity : FunctionMetric {
    override val def =
        MetricDef(
            id = "not-null-assertion-density",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.KOTLIN,
            source = "Moskała, Effective Kotlin",
            rationale =
                "Counts `!!` not-null assertions — each discards Kotlin's null-safety and can throw " +
                    "an NPE. Clusters signal a nullable design that should be made non-null at the boundary.",
            refactorHints =
                listOf(
                    "Replace `!!` with a safe call + Elvis (`?:`) and an explicit fallback.",
                    "Lift nullability to the type: make the property non-null, or use `requireNotNull` once at entry.",
                    "Use `lateinit` for genuinely deferred initialisation instead of nullable + `!!`.",
                ),
            references = listOf("Moskała, M. Effective Kotlin — Item: Avoid `!!`."),
            defaults = Thresholds.warn(3.0),
            structuralReason = "Java has no not-null assertion operator; nullability is unchecked there.",
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double {
        val root = fn.bodyNode ?: fn.node
        return ctx.classifier.tokens(root).count { it.text == "!!" }.toDouble()
    }
}

/**
 * Nested scope functions (`let`/`run`/`apply`/`also`/`with`) obscure which receiver / `it` a line
 * binds to (Moskała). Measures the maximum nesting depth of scope-function lambdas.
 */
class ScopeFunctionNesting : FunctionMetric {
    override val def =
        MetricDef(
            id = "scope-function-nesting",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.KOTLIN,
            source = "Moskała, Effective Kotlin",
            rationale =
                "Deeply nested `let/run/apply/also/with` make the implicit receiver and `it` ambiguous, " +
                    "so a reader can't tell what each line operates on.",
            refactorHints =
                listOf(
                    "Name the intermediate value instead of chaining another scope function.",
                    "Flatten: pull the inner scope-function block into a named local or a private function.",
                ),
            references = listOf("Moskała, M. Effective Kotlin — Item: Limit scope-function nesting."),
            defaults = Thresholds.warn(2.0),
            structuralReason = "Scope functions are a Kotlin stdlib idiom with no Java equivalent.",
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double {
        val root = fn.bodyNode ?: fn.node
        return maxScopeDepth(root, 0, ctx.classifier).toDouble()
    }

    private fun maxScopeDepth(
        node: PsiElement,
        depth: Int,
        c: NodeClassifier,
    ): Int {
        var max = depth
        for (child in c.children(node)) {
            val childDepth = if (c.isScopeFunctionInvocation(child)) depth + 1 else depth
            max = maxOf(max, maxScopeDepth(child, childDepth, c))
        }
        return max
    }
}
