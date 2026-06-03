package dev.ktrics.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** ktrics.yaml validation: severity assignment per problem class. */
class DoctorTest {
    private fun load(
        config: KtricsConfig,
        source: File? = File("ktrics.yaml"),
        problems: List<String> = emptyList(),
    ) = ConfigLoad(config, source, problems, "h")

    private fun List<Doctor.Diagnostic>.errors() = filter { it.severity == Doctor.Severity.ERROR }

    private fun List<Doctor.Diagnostic>.warnings() = filter { it.severity == Doctor.Severity.WARNING }

    private fun List<Doctor.Diagnostic>.infos() = filter { it.severity == Doctor.Severity.INFO }

    @Test
    fun `an unknown metric id is an error`() {
        val diags = Doctor.check(load(KtricsConfig(metrics = mapOf("not-a-metric" to MetricEntry(warning = 1.0)))))
        diags.errors().any { it.message.contains("unknown metric 'not-a-metric'") } shouldBe true
    }

    @Test
    fun `a lower-is-better metric with warning above error is a warning`() {
        // cyclomatic-complexity is LOWER_IS_BETTER, so warning must be <= error.
        val diags =
            Doctor.check(
                load(KtricsConfig(metrics = mapOf("cyclomatic-complexity" to MetricEntry(warning = 20.0, error = 10.0)))),
            )
        diags.warnings().any { it.message.contains("cyclomatic-complexity") && it.message.contains("<=") } shouldBe true
    }

    @Test
    fun `a higher-is-better metric with warning below error is a warning`() {
        // maintainability-index is HIGHER_IS_BETTER, so warning must be >= error.
        val diags =
            Doctor.check(
                load(KtricsConfig(metrics = mapOf("maintainability-index" to MetricEntry(warning = 10.0, error = 20.0)))),
            )
        diags.warnings().any { it.message.contains("maintainability-index") && it.message.contains(">=") } shouldBe true
    }

    @Test
    fun `an informational metric carrying thresholds is a warning`() {
        // instability is INFORMATIONAL — thresholds are meaningless on it.
        val diags =
            Doctor.check(
                load(KtricsConfig(metrics = mapOf("instability" to MetricEntry(warning = 0.5, error = 0.9)))),
            )
        diags.warnings().any { it.message.contains("instability") && it.message.contains("informational") } shouldBe true
    }

    @Test
    fun `a per-language threshold contradiction is flagged with the language suffix`() {
        val diags =
            Doctor.check(
                load(
                    KtricsConfig(
                        metrics =
                            mapOf(
                                "cyclomatic-complexity" to MetricEntry(java = PerLangThresholds(warning = 30.0, error = 10.0)),
                            ),
                    ),
                ),
            )
        diags.warnings().any {
            it.message.contains("cyclomatic-complexity") && it.message.contains("(java)")
        } shouldBe true
    }

    @Test
    fun `an unknown module dependency makes the module graph invalid`() {
        val config =
            KtricsConfig(
                modules =
                    ModulesConfig(
                        declared = listOf(DeclaredModule("app", srcRoots = listOf("app/src"), dependsOn = listOf("ghost"))),
                    ),
            )
        val diags = Doctor.check(load(config))
        diags.errors().any { it.message.contains("module graph invalid") } shouldBe true
    }

    @Test
    fun `an unknown unused preset is a warning, not an error`() {
        val config = KtricsConfig(unused = UnusedConfig(presets = listOf("spring", "made-up")))
        val diags = Doctor.check(load(config))
        diags.warnings().any { it.message.contains("made-up") } shouldBe true
        diags.errors() shouldBe emptyList()
    }

    @Test
    fun `a valid config reports the all-clear info`() {
        val config = KtricsConfig(metrics = mapOf("cyclomatic-complexity" to MetricEntry(warning = 10.0, error = 20.0)))
        val diags = Doctor.check(load(config))
        diags.errors() shouldBe emptyList()
        diags.infos().any { it.message.contains("configuration is valid") } shouldBe true
    }

    @Test
    fun `a missing source file is reported as info, not an error`() {
        val diags = Doctor.check(load(KtricsConfig(), source = null))
        diags.infos().any { it.message.contains("no ktrics.yaml found") } shouldBe true
        diags.errors() shouldBe emptyList()
    }

    @Test
    fun `loader problems surface as errors`() {
        val diags = Doctor.check(load(KtricsConfig(), problems = listOf("failed to parse ktrics.yaml: boom")))
        diags.errors().any { it.message.contains("failed to parse") } shouldBe true
    }
}
