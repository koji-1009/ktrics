package dev.ktrics.metric

import dev.ktrics.metric.clazz.ClassLength
import dev.ktrics.metric.clazz.CouplingBetweenObjects
import dev.ktrics.metric.clazz.DepthOfInheritanceTree
import dev.ktrics.metric.clazz.Lcom4
import dev.ktrics.metric.clazz.NumberOfChildren
import dev.ktrics.metric.clazz.NumberOfMethods
import dev.ktrics.metric.clazz.ResponseForClass
import dev.ktrics.metric.clazz.TopLevelDeclarationsPerFile
import dev.ktrics.metric.clazz.TypesPerFile
import dev.ktrics.metric.clazz.WeightedMethodsPerClass
import dev.ktrics.metric.function.BooleanTrap
import dev.ktrics.metric.function.CognitiveComplexity
import dev.ktrics.metric.function.CyclomaticComplexity
import dev.ktrics.metric.function.HalsteadVolume
import dev.ktrics.metric.function.MaintainabilityIndex
import dev.ktrics.metric.function.MaximumNestingLevel
import dev.ktrics.metric.function.MethodLength
import dev.ktrics.metric.function.NotNullAssertionDensity
import dev.ktrics.metric.function.NpathComplexity
import dev.ktrics.metric.function.NumberOfParameters
import dev.ktrics.metric.function.ScopeFunctionNesting
import dev.ktrics.metric.function.SourceLinesOfCode
import dev.ktrics.metric.pkg.Abstractness
import dev.ktrics.metric.pkg.AfferentCoupling
import dev.ktrics.metric.pkg.DistanceFromMainSequence
import dev.ktrics.metric.pkg.EfferentCoupling
import dev.ktrics.metric.pkg.Instability

/**
 * The builtin metric catalogue (`catalog.kt`). The single registry of which lenses exist,
 * their defaults and their auto-explain text. `ktrics rules` renders it; the engine drives analysis
 * from it. The set that *fires* for a language is governed per-metric by `appliesTo`, decided by
 * structural validity — so the firing set is larger for Java and differs in composition for Kotlin,
 * by construction.
 *
 * Grown incrementally: function-level first; class/file and package lists are appended later.
 */
object BuiltinMetrics {
    /** Function/method level (control-flow shape — structurally language-neutral). */
    val function: List<FunctionMetric> =
        listOf(
            CyclomaticComplexity(),
            CognitiveComplexity(),
            NpathComplexity(),
            MaximumNestingLevel(),
            NumberOfParameters(),
            BooleanTrap(),
            SourceLinesOfCode(),
            MethodLength(),
            NotNullAssertionDensity(),
            ScopeFunctionNesting(),
            HalsteadVolume(),
            MaintainabilityIndex(),
        )

    /** Class/type level — the academic CK suite (full strength on Java). */
    val type: List<TypeMetric> =
        listOf(
            NumberOfMethods(),
            WeightedMethodsPerClass(),
            Lcom4(),
            CouplingBetweenObjects(),
            ResponseForClass(),
            DepthOfInheritanceTree(),
            NumberOfChildren(),
            ClassLength(),
        )

    /** File level — Kotlin-relevant lenses (redundant on Java, so scoped). */
    val file: List<FileMetric> =
        listOf(
            TopLevelDeclarationsPerFile(),
            TypesPerFile(),
        )

    /** Package level — Martin 1994 (applies to both; matches package release-unit granularity). */
    val pkg: List<PackageMetric> =
        listOf(
            EfferentCoupling(),
            AfferentCoupling(),
            Instability(),
            Abstractness(),
            DistanceFromMainSequence(),
        )

    /** Every metric definition across all scopes, in catalogue order. */
    val all: List<MetricDef> by lazy {
        function.map { it.def } + type.map { it.def } + file.map { it.def } + pkg.map { it.def }
    }

    private val byId: Map<String, MetricDef> by lazy { all.associateBy { it.id } }

    fun def(id: String): MetricDef? = byId[id]

    /** All metric ids (used by config doctor to validate threshold keys). */
    fun ids(): Set<String> = byId.keys
}
