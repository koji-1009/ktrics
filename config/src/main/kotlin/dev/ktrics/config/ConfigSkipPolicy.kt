package dev.ktrics.config

import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.SkipPolicy

/**
 * Idiom-aware skips driven by `ktrics.yaml`. Compose-aware, data-class/record-aware,
 * Lombok-builder-aware, and test-aware. Skips suppress the noise lenses on idiomatic shapes while
 * leaving the meaningful ones (CC/cognitive/nparams/coupling) in force.
 */
class ConfigSkipPolicy(private val config: KtricsConfig) : SkipPolicy {
    override fun skipFunction(
        def: MetricDef,
        fn: FunctionDecl,
        owner: TypeDecl?,
        unit: SourceUnit,
    ): Boolean =
        when {
            config.compose && composeSkips(def, fn) -> true
            // Lombok builder: skip parameter count on @Builder constructors / nested Builder constructors.
            def.id == "number-of-parameters" && isBuilderConstructor(fn, owner) -> true
            // data class / record: boolean-trap is noise on a value carrier.
            def.id == "boolean-trap" && owner?.isDataLike == true -> true
            // test files: skip size-and-shape lenses, keep behavioural ones.
            else -> config.test && isTestFile(unit) && def.id in TEST_SKIPPED_SIZE_SHAPE
        }

    /** Compose: `@Preview` skips everything; `@Composable` skips nesting + length only. */
    private fun composeSkips(
        def: MetricDef,
        fn: FunctionDecl,
    ): Boolean =
        when {
            fn.annotations.any { it.endsWith("Preview") } -> true
            else -> fn.annotations.any { it.endsWith("Composable") } && def.id in COMPOSE_SKIPPED
        }

    override fun skipType(
        def: MetricDef,
        type: TypeDecl,
        unit: SourceUnit,
    ): Boolean {
        if (type.isDataLike && def.id in DATA_CLASS_SKIPPED) return true
        if (config.test && isTestFile(unit) && def.id in TEST_SKIPPED_SIZE_SHAPE) return true
        return false
    }

    override fun skipFile(
        def: MetricDef,
        unit: SourceUnit,
    ): Boolean = config.test && isTestFile(unit) && def.id in TEST_SKIPPED_SIZE_SHAPE

    override fun testDslDiscount(unit: SourceUnit): Boolean = config.test && isTestFile(unit)

    private fun isBuilderConstructor(
        fn: FunctionDecl,
        owner: TypeDecl?,
    ): Boolean {
        if (!fn.isConstructor) return false
        if (fn.annotations.any { it.endsWith("Builder") }) return true
        if (owner?.annotations?.any { it.endsWith("Builder") } == true) return true
        return owner?.nested?.any { it.name == "Builder" } == true
    }

    private fun isTestFile(unit: SourceUnit): Boolean = dev.ktrics.metric.TestSources.isTestFile(unit.path)

    private companion object {
        val COMPOSE_SKIPPED = setOf("maximum-nesting-level", "method-length")
        val DATA_CLASS_SKIPPED = setOf("class-length", "number-of-methods")
        val TEST_SKIPPED_SIZE_SHAPE =
            setOf(
                "source-lines-of-code",
                "method-length",
                "maximum-nesting-level",
                "class-length",
                "number-of-methods",
                "top-level-declarations-per-file",
            )
    }
}
