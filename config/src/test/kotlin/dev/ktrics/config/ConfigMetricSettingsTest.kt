package dev.ktrics.config

import dev.ktrics.ir.Lang
import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.metric.Severity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Threshold/enabled resolution order: per-lang config → both config → catalogue. */
class ConfigMetricSettingsTest {
    private val cyclomatic = BuiltinMetrics.def("cyclomatic-complexity")!!
    private val halstead = BuiltinMetrics.def("halstead-volume")!!
    private val nparams = BuiltinMetrics.def("number-of-parameters")!!

    @Test
    fun `an empty config falls through to the catalogue defaults`() {
        val settings = ConfigMetricSettings(KtricsConfig())
        settings.thresholds(cyclomatic, Lang.KOTLIN) shouldBe cyclomatic.thresholdsFor(Lang.KOTLIN)
        settings.isEnabled(cyclomatic) shouldBe true
    }

    @Test
    fun `a both-language config override applies to every language`() {
        val config = KtricsConfig(metrics = mapOf("cyclomatic-complexity" to MetricEntry(warning = 7.0, error = 14.0)))
        val settings = ConfigMetricSettings(config)
        settings.thresholds(cyclomatic, Lang.JAVA).warning shouldBe 7.0
        settings.thresholds(cyclomatic, Lang.KOTLIN).error shouldBe 14.0
    }

    @Test
    fun `a per-language override beats the both-language override and the other language keeps it`() {
        val config =
            KtricsConfig(
                metrics =
                    mapOf(
                        "cyclomatic-complexity" to
                            MetricEntry(
                                warning = 7.0,
                                kotlin = PerLangThresholds(warning = 12.0),
                            ),
                    ),
            )
        val settings = ConfigMetricSettings(config)
        // Kotlin takes the per-language warning; Java falls back to the both-language override.
        settings.thresholds(cyclomatic, Lang.KOTLIN).warning shouldBe 12.0
        settings.thresholds(cyclomatic, Lang.JAVA).warning shouldBe 7.0
    }

    @Test
    fun `a partial per-language override only replaces the side it sets`() {
        val config =
            KtricsConfig(
                metrics =
                    mapOf(
                        "cyclomatic-complexity" to
                            MetricEntry(
                                warning = 7.0,
                                error = 14.0,
                                kotlin = PerLangThresholds(error = 30.0),
                            ),
                    ),
            )
        val t = ConfigMetricSettings(config).thresholds(cyclomatic, Lang.KOTLIN)
        t.warning shouldBe 7.0 // not overridden per-language → both-language override
        t.error shouldBe 30.0 // overridden per-language
    }

    @Test
    fun `the catalogue per-language nparams threshold fires for Java but not Kotlin at five params`() {
        // End-to-end through severityFor: nparams base warn=4, Kotlin per-language warn=6. Five params
        // breaches Java's warning but stays under Kotlin's — the whole point of the per-language override.
        val settings = ConfigMetricSettings(KtricsConfig()) // no overrides → catalogue defaults
        settings.thresholds(nparams, Lang.JAVA).severityFor(5.0, nparams.polarity) shouldBe Severity.WARNING
        settings.thresholds(nparams, Lang.KOTLIN).severityFor(5.0, nparams.polarity) shouldBe null
    }

    @Test
    fun `enabled turns an off-by-default metric on and false disables a default-on metric`() {
        halstead.enabledByDefault shouldBe false
        ConfigMetricSettings(KtricsConfig()).isEnabled(halstead) shouldBe false

        val on = ConfigMetricSettings(KtricsConfig(metrics = mapOf("halstead-volume" to MetricEntry(enabled = true))))
        on.isEnabled(halstead) shouldBe true

        val off = ConfigMetricSettings(KtricsConfig(metrics = mapOf("number-of-parameters" to MetricEntry(enabled = false))))
        off.isEnabled(nparams) shouldBe false
    }
}
