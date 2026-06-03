package dev.ktrics.config

import dev.ktrics.ir.Lang
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConfigLoaderTest {
    @Test
    fun `parses thresholds, per-language overrides and the metric-false shorthand`() {
        val yaml =
            """
            ktrics:
              compose: true
              test: true
              resolution: name-based
              metrics:
                cyclomatic-complexity: { warning: 10, error: 20 }
                number-of-parameters: { warning: 4, kotlin: { warning: 6 } }
                response-for-class: false
              unused:
                presets: [spring, unknown-preset]
            """.trimIndent()
        val config = ConfigLoader.parse(yaml)

        config.compose shouldBe true
        config.test shouldBe true
        config.resolution shouldBe ResolutionMode.NAME_BASED
        config.metrics["cyclomatic-complexity"]!!.warning shouldBe 10.0
        config.metrics["number-of-parameters"]!!.kotlin!!.warning shouldBe 6.0
        config.metrics["response-for-class"]!!.enabled shouldBe false
        config.unused.presets shouldBe listOf("spring", "unknown-preset")
    }

    @Test
    fun `config metric settings apply per-language and enabled overrides`() {
        val config =
            ConfigLoader.parse(
                """
                ktrics:
                  metrics:
                    halstead-volume: { enabled: true, warning: 1500 }
                    number-of-parameters: { kotlin: { warning: 6 } }
                """.trimIndent(),
            )
        val settings = ConfigMetricSettings(config)
        val halstead = dev.ktrics.metric.BuiltinMetrics.def("halstead-volume")!!
        val nparams = dev.ktrics.metric.BuiltinMetrics.def("number-of-parameters")!!

        settings.isEnabled(halstead) shouldBe true // off-by-default metric turned on
        settings.thresholds(nparams, Lang.KOTLIN).warning shouldBe 6.0
        settings.thresholds(nparams, Lang.JAVA).warning shouldBe 4.0 // catalogue default
    }

    @Test
    fun `unknown metric is a doctor error`() {
        val load = ConfigLoad(ConfigLoader.parse("ktrics:\n  metrics:\n    not-a-metric: { warning: 1 }"), null, emptyList(), "h")
        val diagnostics = Doctor.check(load)
        diagnostics.any { it.severity == Doctor.Severity.ERROR && it.message.contains("not-a-metric") } shouldBe true
    }

    @Test
    fun `presets expand to keep-alive annotations and ignore unknowns`() {
        val keep = Presets.keepAliveAnnotations(listOf("spring", "made-up"), listOf("Keep"))
        keep.contains("Component") shouldBe true
        keep.contains("Keep") shouldBe true
    }
}
