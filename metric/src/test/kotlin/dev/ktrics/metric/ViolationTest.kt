package dev.ktrics.metric

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.Span
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The violation model — the shape every reporter renders and regression diffs. */
class ViolationTest {
    private val span = Span("src/Foo.kt", 10, 1, 12, 1, 0, 0)
    private val def =
        MetricDef(
            id = "cyclomatic-complexity", scopeKind = ScopeKind.FUNCTION, polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH, source = "McCabe 1976", rationale = "counts paths",
            refactorHints = listOf("extract"), references = listOf("McCabe 1976"),
            defaults = Thresholds.warnError(10.0, 20.0),
        )

    private fun result(resolution: Resolution? = null) =
        MetricResult(
            metricId = def.id, file = "src/Foo.kt", scope = "com.x.Foo.bar", scopeName = "Foo.bar()",
            scopeKind = ScopeKind.FUNCTION, lang = Lang.KOTLIN, value = 30.0, resolution = resolution, span = span,
        )

    @Test
    fun `from copies the measurement and grafts the def's auto-explain inline`() {
        val v = Violation.from(result(), def, Severity.ERROR, threshold = 20.0)
        v.metricId shouldBe "cyclomatic-complexity"
        v.severity shouldBe Severity.ERROR
        v.polarity shouldBe Polarity.LOWER_IS_BETTER
        v.value shouldBe 30.0
        v.threshold shouldBe 20.0
        v.rationale shouldBe "counts paths"
        v.refactorHints shouldBe listOf("extract")
        v.references shouldBe listOf("McCabe 1976")
        v.span shouldBe span
        v.dismissal shouldBe DismissalState.None
        v.complexityJustified shouldBe false
    }

    @Test
    fun `the stable id is derived from file, scope and metric`() {
        val a = Violation.from(result(), def, Severity.ERROR, 20.0)
        val b = Violation.from(result(), def, Severity.WARNING, 10.0)
        // Same (file, scope, metric) → same id regardless of severity/threshold/value.
        a.id shouldBe b.id
        a.id shouldBe StableId.of("src/Foo.kt", "com.x.Foo.bar", "cyclomatic-complexity")
    }

    @Test
    fun `the resolution stamp carries through from the measurement`() {
        Violation.from(result(Resolution.NAME_BASED), def, Severity.WARNING, 10.0).resolution shouldBe
            Resolution.NAME_BASED
        Violation.from(result(null), def, Severity.WARNING, 10.0).resolution shouldBe null
    }

    @Test
    fun `dismissal states model the three outcomes`() {
        val dismissed = DismissalState.Dismissed(reason = "reviewed", source = "sidecar")
        dismissed.reason shouldBe "reviewed"
        dismissed.source shouldBe "sidecar"
        val rejected = DismissalState.Rejected(reason = "wip", minReasonLength = 12)
        rejected.minReasonLength shouldBe 12
        (DismissalState.None is DismissalState) shouldBe true
    }
}
