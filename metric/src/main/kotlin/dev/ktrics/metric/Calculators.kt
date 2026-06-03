package dev.ktrics.metric

import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl
import dev.ktrics.langapi.NodeClassifier

/**
 * Everything a calculator needs at a measurement site. The [classifier] is already the right one for
 * the scope's language (the engine selects it), so calculators stay language-free.
 * [index] is the module-aware project view for cross-file/cross-module metrics (DIT/NOC, Martin
 * Ce/Ca); null in the function-level-only phase where no global data is required.
 */
class MeasureContext(
    val classifier: NodeClassifier,
    val unit: SourceUnit,
    val index: ProjectIndex? = null,
)

/**
 * Module-aware project index queried by class- and package-level calculators.
 * "External" is defined by the module graph, never by guesswork. Implemented by :engine;
 * fed name-based data initially and resolved data once resolution is on, with NO calculator change.
 */
interface ProjectIndex {
    /** Packages produced by in-graph source modules; everything else is external (Martin Ce). */
    val internalPackages: Set<String>

    /** The in-project type declaration for a qualified name, or null when external/unresolved. */
    fun typeByQName(qualifiedName: String): TypeDecl?

    /** Resolved qualified names of the DIRECT supertypes of [typeQName] (DIT/NOC traversal). */
    fun directSupertypeQNames(typeQName: String): List<String>

    /** Whether [typeQName]'s inheritance edges were resolution-backed (stamped on DIT/NOC). */
    fun inheritanceResolution(typeQName: String): Resolution

    /** NOC: number of resolved incoming supertype edges to [typeQName]. */
    fun childrenCountOf(typeQName: String): Int

    /** Ca: distinct in-project packages that depend on [pkg]. */
    fun afferentPackagesOf(pkg: String): Set<String>

    /** Ce: distinct external packages imported by [pkg] (external per the module graph). */
    fun efferentPackagesOf(pkg: String): Set<String>

    /** Every type declared in [pkg], across all units (abstractness A). */
    fun typesInPackage(pkg: String): List<TypeDecl>
}

/** A metric measured per function/method. Purely syntactic — no resolution. */
interface FunctionMetric {
    val def: MetricDef

    /** Returns the measured value, or null when the metric does not apply to this particular scope. */
    fun measure(
        fn: FunctionDecl,
        ctx: MeasureContext,
    ): Double?
}

/** A metric measured per type. Coupling/inheritance lenses stamp [resolution]. */
interface TypeMetric {
    val def: MetricDef

    fun measure(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Double?

    /** Confidence of this measurement, for coupling/cohesion lenses; null for purely structural ones. */
    fun resolution(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Resolution? = null
}

/** A metric measured per file (Kotlin-relevant). */
interface FileMetric {
    val def: MetricDef

    fun measure(
        unit: SourceUnit,
        ctx: MeasureContext,
    ): Double?
}

/** A metric measured per package (Martin package level). */
interface PackageMetric {
    val def: MetricDef

    fun measure(
        pkg: PackageUnit,
        ctx: MeasureContext,
    ): Double?

    fun resolution(
        pkg: PackageUnit,
        ctx: MeasureContext,
    ): Resolution? = null
}

/**
 * A package aggregate, assembled by the engine across all units in one package. Carries
 * what the Martin metrics need: the declared types and the imports seen, partitioned into
 * internal/external by the module graph (via the [ProjectIndex]).
 */
class PackageUnit(
    val name: String,
    val types: List<TypeDecl>,
    /** All import targets (fully-qualified) seen across the package's files. */
    val importedQualifiedNames: List<String>,
    /** A representative span (first file) so a package violation has a location to point at. */
    val span: dev.ktrics.ir.Span,
    /** Representative language for threshold lookup; packages are language-neutral so defaults apply. */
    val lang: dev.ktrics.ir.Lang,
)
