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
 * structure + B2 nesting penalty + B3 logical-operator sequences. Per the spec: `else if`/`else`
 * and labeled jumps charge flat (+1, no nesting); lambdas and local functions raise the nesting
 * level without an increment of their own; `?.` alone adds nothing (handled in the Kotlin
 * classifier). On test files (with `test:` on) closures passed as call arguments are skipped
 * entirely — the test-DSL discount (see doc/calibration.md).
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
        return walk(root, depth = 0, c, skipArgumentClosures = ctx.isTestFile).toDouble()
    }

    private fun walk(
        node: PsiElement,
        depth: Int,
        c: NodeClassifier,
        skipArgumentClosures: Boolean,
    ): Int {
        // Test-DSL discount: a closure handed to a call (`test("x") { … }`) is data handed to the
        // DSL, not control flow of the enclosing function — skip its contents AND its nesting.
        if (skipArgumentClosures && c.isArgumentClosure(node)) return 0
        var total = increment(node, depth, c)
        val childDepth = if (raisesChildNesting(node, c)) depth + 1 else depth
        for (child in c.children(node)) {
            total += walk(child, childDepth, c, skipArgumentClosures)
        }
        return total
    }

    /**
     * The increment one node contributes, scored before recursing: an expression-body control
     * structure (Kotlin `= if`/`when`) IS the root, so a children-only walk would never charge the
     * top-level structure its B1. The flat check precedes the nesting one — an `else if` is still an
     * if node, but charges +1 with no nesting penalty. A plain `else` charges +1 flat through its
     * owning if (the else keyword has no node of its own).
     */
    private fun increment(
        node: PsiElement,
        depth: Int,
        c: NodeClassifier,
    ): Int {
        val structural =
            when {
                c.isFlatIncrement(node) -> 1 // B1 only: `else if` link / labeled jump
                c.isNestingBoundary(node) -> 1 + depth // B1 increment + B2 nesting penalty
                c.isLogicalSequence(node) -> 1 // B3: one per run of like operators
                else -> 0
            }
        return structural + c.elseIncrement(node)
    }

    /**
     * Whether [node]'s children sit one nesting level deeper: control structures nest, and so do
     * lambdas/local functions (without an increment of their own) — while an `else if` keeps its
     * inherited depth (it was already bumped as a child of the chain head; the chain shares one level).
     */
    private fun raisesChildNesting(
        node: PsiElement,
        c: NodeClassifier,
    ): Boolean = (!c.isFlatIncrement(node) && c.isNestingBoundary(node)) || c.isNestingOnlyBoundary(node)
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
        // An `else if` link (isFlatIncrement) shares the chain head's level — a flat 4-branch chain is depth 1,
        // not 3. Lambdas deliberately do NOT count here (unlike cognitive's B2): this lens measures
        // CONTROL-STRUCTURE depth, and counting builder-DSL lambdas (`buildJsonArray { forEach { … } }`)
        // would fire it on flat, idiomatic code — confirmed by dogfooding (doc/calibration.md).
        val bumps = c.isNestingBoundary(node) && !c.isFlatIncrement(node)
        val here = if (bumps) depth + 1 else depth
        var max = here
        for (child in c.children(node)) {
            max = maxOf(max, maxDepth(child, here, c))
        }
        return max
    }
}
