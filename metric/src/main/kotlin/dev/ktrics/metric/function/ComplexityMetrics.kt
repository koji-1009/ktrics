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
 * Control-flow shape metrics — structurally language-neutral, so written once over the classifier.
 * Per-language counting nuances live in the classifier impls;
 * every deviation from the cited paper is recorded in doc/calibration.md.
 */

/** McCabe 1976 cyclomatic complexity: `1 + d` where d is the number of decision points. */
class CyclomaticComplexity : FunctionMetric {
    override val def =
        MetricDef(
            id = "cyclomatic-complexity",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "McCabe 1976",
            rationale =
                "Counts independent paths through a method (1 + decision points). High values mean " +
                    "more branches to test and reason about; the loop should consider extracting or flattening.",
            refactorHints =
                listOf(
                    "Extract a cohesive branch into a well-named private method.",
                    "Replace nested conditionals with early returns (guard clauses).",
                    "Replace a type-switch with polymorphism where the cases model subtypes.",
                ),
            references = listOf("McCabe, T. (1976). A Complexity Measure. IEEE TSE."),
            defaults = Thresholds.warnError(warn = 10.0, err = 20.0),
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double {
        val c = ctx.classifier
        val root = fn.bodyNode ?: fn.node
        // decisionWeight (not a plain node count) so Java's grouped `a && b && c` counts both operators.
        // Include `root` itself, not only its descendants: an expression-body branch (Kotlin `= if`/`when`/
        // `&&`) IS the root, so a descendants-only walk would miss the top-level decision (mirrors NPATH).
        val decisions = (sequenceOf(root) + c.descendants(root)).sumOf { c.decisionWeight(it) }
        return (1 + decisions).toDouble()
    }
}

/**
 * SonarSource 2018 cognitive complexity (industry, not peer-reviewed). B1 increment per control
 * structure + B2 nesting penalty + B3 logical-operator sequences. Nesting penalty applies inside
 * lambdas and Kotlin scope functions; `?.` alone adds nothing (handled in the Kotlin classifier).
 * Deviations from the spec (else/label handling) are documented in doc/calibration.md.
 */
class CognitiveComplexity : FunctionMetric {
    override val def =
        MetricDef(
            id = "cognitive-complexity",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "SonarSource 2018",
            rationale =
                "Approximates how hard a method is to *understand*: each control structure costs 1, " +
                    "and nesting it deeper costs more. Unlike cyclomatic, deeply nested logic is penalised harder.",
            refactorHints =
                listOf(
                    "Reduce nesting: invert conditions and return early.",
                    "Extract a nested block into its own method (resets the nesting penalty).",
                    "Break a long boolean expression into well-named intermediate values.",
                ),
            references = listOf("Campbell, G.A. (2018). Cognitive Complexity. SonarSource."),
            defaults = Thresholds.warn(15.0),
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double {
        val c = ctx.classifier
        val root = fn.bodyNode ?: fn.node
        return walk(root, depth = 0, c).toDouble()
    }

    private fun walk(
        node: PsiElement,
        depth: Int,
        c: NodeClassifier,
    ): Int {
        // Score `node` itself before recursing: an expression-body control structure (Kotlin `= if`/`when`)
        // IS the root, so a children-only walk would never charge the top-level structure its B1 increment.
        // The function's own top-level block is not a nesting boundary, so block bodies are unaffected.
        val nests = c.isNestingBoundary(node)
        var total =
            when {
                nests -> 1 + depth // B1 increment + B2 nesting penalty
                c.isLogicalSequence(node) -> 1 // B3: one per run of like operators
                else -> 0
            }
        val childDepth = if (nests) depth + 1 else depth
        for (child in c.children(node)) {
            total += walk(child, childDepth, c)
        }
        return total
    }
}

/**
 * Nejmeh 1988 NPATH: the number of acyclic execution paths. Computed as the product of per-branch
 * multipliers over the body (binary branches ×2, multi-way switch/when ×cases, loops ×2). This is a
 * deterministic approximation of the full recursive formula; the simplification is documented in
 * doc/calibration.md, as the sibling tools do.
 */
class NpathComplexity : FunctionMetric {
    override val def =
        MetricDef(
            id = "npath-complexity",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "Nejmeh 1988",
            rationale =
                "Estimates the number of distinct paths through a method — it grows multiplicatively " +
                    "with branching, exposing combinatorial blow-up that cyclomatic (additive) understates. " +
                    "Measure-only by default: the product-form approximation over-counts independent guard " +
                    "clauses (each early-return guard multiplies the estimate though only one path is taken), " +
                    "so cyclomatic + cognitive carry the branch-complexity gate; set a threshold to enforce npath.",
            refactorHints =
                listOf(
                    "Split the method so independent branch clusters live in separate methods.",
                    "Collapse redundant conditionals; hoist invariant checks out of loops.",
                ),
            references = listOf("Nejmeh, B. (1988). NPATH: a measure of execution path complexity. CACM."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double {
        val c = ctx.classifier
        val root = fn.bodyNode ?: fn.node
        var product = 1L
        // Include `root` itself, not only its descendants: an expression-body branch (Kotlin `= when`, a
        // single-statement Java switch) IS the root, so a descendants-only walk would miss the top-level
        // multi-way branch entirely and report npath 1 for it.
        for (n in sequenceOf(root) + c.descendants(root)) {
            if (c.isLoopOrBranch(n)) {
                product *= c.npathMultiplier(n).coerceAtLeast(1).toLong()
                if (product > NPATH_CAP) return NPATH_CAP.toDouble() // avoid overflow on pathological bodies
            }
        }
        return product.toDouble()
    }

    private companion object {
        const val NPATH_CAP = 1_000_000_000L
    }
}

/** Maximum block-nesting depth reached in a method body. */
class MaximumNestingLevel : FunctionMetric {
    override val def =
        MetricDef(
            id = "maximum-nesting-level",
            scopeKind = ScopeKind.FUNCTION,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "—",
            rationale =
                "The deepest level of nested control structures. Deep nesting is a strong, simple " +
                    "signal that a method is doing too much at once.",
            refactorHints =
                listOf(
                    "Apply guard clauses to flatten the happy path.",
                    "Extract the innermost block into a method.",
                ),
            references = emptyList(),
            defaults = Thresholds.warn(4.0),
        )

    override fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double {
        val c = ctx.classifier
        val root = fn.bodyNode ?: fn.node
        return maxDepth(root, 0, c).toDouble()
    }

    private fun maxDepth(
        node: PsiElement,
        depth: Int,
        c: NodeClassifier,
    ): Int {
        // Count `node` itself as a level, not only its children: an expression-body control structure IS the
        // root. The function's own top-level block is not a nesting boundary, so block bodies still start at 0.
        val here = if (c.isNestingBoundary(node)) depth + 1 else depth
        var max = here
        for (child in c.children(node)) {
            max = maxOf(max, maxDepth(child, here, c))
        }
        return max
    }
}
