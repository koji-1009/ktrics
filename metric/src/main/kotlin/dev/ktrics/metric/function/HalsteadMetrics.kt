package dev.ktrics.metric.function

import com.intellij.psi.PsiElement
import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.TokenKind
import dev.ktrics.langapi.NodeClassifier
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.FunctionMetric
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Thresholds
import dev.ktrics.metric.text.SourceLines
import kotlin.math.ln
import kotlin.math.log2

/** Shared Halstead token counts, reused by volume and the maintainability index. */
internal data class Halstead(
    val distinctOperators: Int,
    val distinctOperands: Int,
    val totalOperators: Int,
    val totalOperands: Int,
) {
    val vocabulary: Int get() = distinctOperators + distinctOperands
    val length: Int get() = totalOperators + totalOperands

    /** V = N · log2(n). Zero when there is no vocabulary (an empty body). */
    val volume: Double get() = if (vocabulary == 0) 0.0 else length * log2(vocabulary.toDouble())

    companion object {
        fun of(
            node: PsiElement,
            c: NodeClassifier,
        ): Halstead {
            val tokens = c.tokens(node)
            val operators = tokens.filter { it.kind == TokenKind.OPERATOR }
            val operands = tokens.filter { it.kind == TokenKind.OPERAND }
            return Halstead(
                distinctOperators = operators.map { it.text }.distinct().size,
                distinctOperands = operands.map { it.text }.distinct().size,
                totalOperators = operators.size,
                totalOperands = operands.size,
            )
        }
    }
}

/** Halstead 1977 volume. Historical/token-based; OFF by default, opt in via config. */
class HalsteadVolume : FunctionMetric {
    override val def =
        MetricDef(
            id = "halstead-volume",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "Halstead 1977",
            rationale =
                "Token-based size estimate (length × log2 vocabulary). Historical; correlates loosely " +
                    "with effort. Off by default — enable deliberately when you want a token-weighted size lens.",
            refactorHints = listOf("Reduce distinct operators/operands by extracting sub-expressions."),
            references = listOf("Halstead, M. (1977). Elements of Software Science."),
            defaults = Thresholds.warn(1500.0),
            enabledByDefault = false,
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double {
        val root = fn.bodyNode ?: fn.node
        return Halstead.of(root, ctx.classifier).volume
    }
}

/**
 * Oman 1992 maintainability index (SEI normalised variant, 0–100). Composite and contested; OFF by
 * default. Higher is better. Combines Halstead volume, cyclomatic complexity and SLOC.
 */
class MaintainabilityIndex : FunctionMetric {
    override val def =
        MetricDef(
            id = "maintainability-index",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.HIGHER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "Oman 1992",
            rationale =
                "A 0–100 composite of Halstead volume, cyclomatic complexity and SLOC. Contested and " +
                    "easy to game; off by default. When enabled, treat low values as a prompt to investigate, not a gate.",
            refactorHints = listOf("Lower the inputs it aggregates: reduce branches, shorten the body, simplify expressions."),
            references = listOf("Oman, P. & Hagemeister, J. (1992). Metrics for assessing maintainability."),
            defaults = Thresholds(warning = 65.0, error = 20.0),
            enabledByDefault = false,
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double {
        val c = ctx.classifier
        val root = fn.bodyNode ?: fn.node
        val volume = Halstead.of(root, c).volume.coerceAtLeast(1.0)
        // Include `root` itself (expression-body branch IS the root), matching CyclomaticComplexity.
        val cc = 1 + (sequenceOf(root) + c.descendants(root)).sumOf { c.decisionWeight(it) }
        val sloc = SourceLines.count(c.text(root)).coerceAtLeast(1)
        // SEI normalised MI, clamped to [0, 100].
        val raw = 171.0 - 5.2 * ln(volume) - 0.23 * cc - 16.2 * ln(sloc.toDouble())
        return (raw * 100.0 / 171.0).coerceIn(0.0, 100.0)
    }
}
