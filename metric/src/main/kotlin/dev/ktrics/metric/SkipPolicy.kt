package dev.ktrics.metric

import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl

/**
 * Idiom-aware skips. Some lenses are noise on idiomatic shapes: a Compose `@Composable`
 * shouldn't trip nesting/length; a `data class` / `record` shouldn't trip class-length/NOM/boolean-trap;
 * a Lombok `@Builder` shouldn't trip parameter count; test files shouldn't trip size-and-shape lenses.
 *
 * The engine consults this in addition to the `appliesTo` gate. The default skips nothing; the config
 * module supplies an implementation driven by `compose`/`test`/data-class/builder rules.
 */
interface SkipPolicy {
    /** [owner] is the declaring type, or null for a Kotlin top-level function. */
    fun skipFunction(
        def: MetricDef,
        fn: FunctionDecl,
        owner: TypeDecl?,
        unit: SourceUnit,
    ): Boolean = false

    fun skipType(
        def: MetricDef,
        type: TypeDecl,
        unit: SourceUnit,
    ): Boolean = false

    fun skipFile(
        def: MetricDef,
        unit: SourceUnit,
    ): Boolean = false

    object None : SkipPolicy
}
