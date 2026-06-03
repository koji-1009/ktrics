package dev.ktrics.engine

import dev.ktrics.coverage.CoverageData
import dev.ktrics.coverage.MethodCoverage
import dev.ktrics.ir.Lang
import dev.ktrics.ir.Span
import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.metric.MetricResult
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Severity
import dev.ktrics.metric.Violation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** complexityJustified stamping for well-covered complexity violations. */
class CoverageAnnotatorTest {
    private fun violation(
        metric: String,
        scope: String,
    ): Violation {
        val def = BuiltinMetrics.def(metric)!!
        val span = Span("src/Foo.kt", 1, 1, 1, 1, 0, 0)
        val result = MetricResult(metric, "src/Foo.kt", scope, scope, ScopeKind.FUNCTION, Lang.KOTLIN, 30.0, null, span)
        return Violation.from(result, def, Severity.ERROR, threshold = 20.0)
    }

    private fun coverage(
        scope: String,
        branchCovered: Int,
        branchMissed: Int,
    ) = CoverageData(mapOf(scope to MethodCoverage(branchCovered, branchMissed, 0, 0)))

    @Test
    fun `a well-covered complexity violation is stamped justified`() {
        val v = violation("cyclomatic-complexity", "com.x.Foo.bar")
        // 9/10 branch coverage ≥ 0.8.
        val annotated = CoverageAnnotator.annotate(listOf(v), coverage("com.x.Foo.bar", 9, 1)).single()
        annotated.complexityJustified shouldBe true
    }

    @Test
    fun `a poorly-covered complexity violation is not stamped`() {
        val v = violation("cyclomatic-complexity", "com.x.Foo.bar")
        val annotated = CoverageAnnotator.annotate(listOf(v), coverage("com.x.Foo.bar", 2, 8)).single()
        annotated.complexityJustified shouldBe false
    }

    @Test
    fun `cognitive and npath complexity violations are also stamped, not just cyclomatic`() {
        // COMPLEXITY_METRICS covers all three branch-complexity lenses; the others were untested.
        listOf("cognitive-complexity", "npath-complexity").forEach { metric ->
            val v = violation(metric, "com.x.Foo.bar")
            CoverageAnnotator.annotate(listOf(v), coverage("com.x.Foo.bar", 9, 1)).single().complexityJustified shouldBe true
        }
    }

    @Test
    fun `a non-complexity metric is never stamped, even when well-covered`() {
        val v = violation("number-of-parameters", "com.x.Foo.bar")
        val annotated = CoverageAnnotator.annotate(listOf(v), coverage("com.x.Foo.bar", 10, 0)).single()
        annotated.complexityJustified shouldBe false
    }

    @Test
    fun `empty coverage leaves the violations untouched`() {
        val v = violation("cyclomatic-complexity", "com.x.Foo.bar")
        CoverageAnnotator.annotate(listOf(v), CoverageData.EMPTY).single() shouldBe v
    }
}
