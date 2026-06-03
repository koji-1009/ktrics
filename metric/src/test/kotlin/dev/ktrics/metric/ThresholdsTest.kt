package dev.ktrics.metric

import dev.ktrics.ir.Lang
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ThresholdsTest {
    @Test
    fun `lower-is-better fires error at or above the error bound`() {
        val t = Thresholds.warnError(warn = 10.0, err = 20.0)
        t.severityFor(9.0, Polarity.LOWER_IS_BETTER) shouldBe null
        t.severityFor(10.0, Polarity.LOWER_IS_BETTER) shouldBe Severity.WARNING
        t.severityFor(19.0, Polarity.LOWER_IS_BETTER) shouldBe Severity.WARNING
        t.severityFor(20.0, Polarity.LOWER_IS_BETTER) shouldBe Severity.ERROR
    }

    @Test
    fun `higher-is-better fires when value drops to or below the bound`() {
        val t = Thresholds(warning = 65.0, error = 20.0)
        t.severityFor(66.0, Polarity.HIGHER_IS_BETTER) shouldBe null
        t.severityFor(65.0, Polarity.HIGHER_IS_BETTER) shouldBe Severity.WARNING
        t.severityFor(20.0, Polarity.HIGHER_IS_BETTER) shouldBe Severity.ERROR
    }

    @Test
    fun `informational never fires`() {
        Thresholds.warnError(1.0, 2.0).severityFor(100.0, Polarity.INFORMATIONAL) shouldBe null
    }

    @Test
    fun `per-language override merges over both-language defaults`() {
        val def =
            MetricDef(
                id = "number-of-parameters", scopeKind = ScopeKind.FUNCTION, polarity = Polarity.LOWER_IS_BETTER,
                appliesTo = AppliesTo.BOTH, source = "—", rationale = "", refactorHints = emptyList(),
                references = emptyList(), defaults = Thresholds.warnError(4.0, 8.0),
                perLanguage = mapOf("kotlin" to Thresholds(warning = 6.0)),
            )
        def.thresholdsFor(Lang.JAVA).warning shouldBe 4.0
        def.thresholdsFor(Lang.KOTLIN).warning shouldBe 6.0
        def.thresholdsFor(Lang.KOTLIN).error shouldBe 8.0 // inherited from both-language default
    }
}
