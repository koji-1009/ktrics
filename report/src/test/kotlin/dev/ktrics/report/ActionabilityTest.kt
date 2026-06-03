package dev.ktrics.report

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Span
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Severity
import dev.ktrics.metric.StableId
import dev.ktrics.metric.Violation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Actionability sort: severity, then polarity-aware breach ratio, then stable tie-breakers. */
class ActionabilityTest {
    private fun v(
        metric: String,
        severity: Severity,
        value: Double,
        threshold: Double,
        polarity: Polarity = Polarity.LOWER_IS_BETTER,
        file: String = "src/Foo.kt",
        line: Int = 10,
    ): Violation =
        Violation(
            id = StableId.of(file, "com.x.$metric", metric),
            metricId = metric, severity = severity, polarity = polarity, appliesTo = AppliesTo.BOTH,
            file = file, scope = "com.x.$metric", scopeName = "Foo.$metric", scopeKind = ScopeKind.FUNCTION,
            lang = Lang.KOTLIN, value = value, threshold = threshold,
            span = Span(file, line, 1, line, 1, 0, 0),
            rationale = "r", refactorHints = emptyList(), references = emptyList(),
        )

    @Test
    fun `errors sort before warnings regardless of breach size`() {
        val bigWarn = v("a", Severity.WARNING, 100.0, 10.0)
        val smallError = v("b", Severity.ERROR, 21.0, 20.0)
        Actionability.sort(listOf(bigWarn, smallError)).first().severity shouldBe Severity.ERROR
    }

    @Test
    fun `for lower-is-better a larger value over threshold ranks higher`() {
        val mild = v("mild", Severity.WARNING, 11.0, 10.0) // 1.1x
        val severe = v("severe", Severity.WARNING, 40.0, 10.0) // 4x
        Actionability.sort(listOf(mild, severe)).first().metricId shouldBe "severe"
    }

    @Test
    fun `for higher-is-better a smaller value ranks higher`() {
        // HIGHER_IS_BETTER breach ratio is threshold/value, so the worse (smaller) value sorts first.
        val nearMiss = v("near", Severity.WARNING, 18.0, 20.0, polarity = Polarity.HIGHER_IS_BETTER) // 1.11
        val farMiss = v("far", Severity.WARNING, 4.0, 20.0, polarity = Polarity.HIGHER_IS_BETTER) // 5.0
        Actionability.sort(listOf(nearMiss, farMiss)).first().metricId shouldBe "far"
    }

    @Test
    fun `a higher-is-better value of zero is treated as the most actionable`() {
        val zero = v("zero", Severity.WARNING, 0.0, 20.0, polarity = Polarity.HIGHER_IS_BETTER)
        val other = v("other", Severity.WARNING, 5.0, 20.0, polarity = Polarity.HIGHER_IS_BETTER)
        Actionability.sort(listOf(other, zero)).first().metricId shouldBe "zero"
    }

    @Test
    fun `a zero threshold falls back to the raw value for ranking`() {
        // threshold == 0 would divide-by-zero; the ranking uses the raw value instead.
        val small = v("small", Severity.WARNING, 2.0, 0.0)
        val large = v("large", Severity.WARNING, 9.0, 0.0)
        Actionability.sort(listOf(small, large)).first().metricId shouldBe "large"
    }

    @Test
    fun `informational metrics fall to the bottom but keep a stable order`() {
        val info1 = v("zinfo", Severity.WARNING, 0.9, 0.5, polarity = Polarity.INFORMATIONAL)
        val info2 = v("ainfo", Severity.WARNING, 0.9, 0.5, polarity = Polarity.INFORMATIONAL)
        // Equal (zero) breach ratio → tie broken by file then line then metricId (ainfo before zinfo).
        Actionability.sort(listOf(info1, info2)).map { it.metricId } shouldBe listOf("ainfo", "zinfo")
    }

    @Test
    fun `equal breaches break ties by file then line`() {
        val later = v("m", Severity.WARNING, 20.0, 10.0, file = "src/Z.kt", line = 1)
        val earlier = v("m", Severity.WARNING, 20.0, 10.0, file = "src/A.kt", line = 99)
        Actionability.sort(listOf(later, earlier)).first().file shouldBe "src/A.kt"
    }
}
