package dev.ktrics.engine

import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl
import dev.ktrics.metric.ProjectIndex

/**
 * The module-aware project index. Built once from the lowered [SourceUnit]s, it answers
 * the cross-file/cross-module queries class- and package-level calculators need. "External" is decided
 * by the module graph: a package no in-graph source module declares is external.
 *
 * Initial phase feeds it name-based supertypes (resolved by simple-name matching against imports and the
 * same package); later phases can swap in resolved qualified names with no calculator change.
 */
class ProjectIndexImpl(units: List<SourceUnit>) : ProjectIndex {
    private val allTypes: List<TypeDecl> = units.flatMap { it.allTypes() }
    private val typeByQn: Map<String, TypeDecl> = allTypes.mapNotNull { t -> t.qualifiedName?.let { it to t } }.toMap()
    private val typesBySimpleName: Map<String, List<TypeDecl>> = allTypes.groupBy { it.name }

    override val internalPackages: Set<String> = units.map { it.packageName }.toSet()

    /** Direct supertype qnames, resolving simple names against imports + same-package types. */
    private val supertypeQns: Map<String, List<String>> =
        buildMap {
            for (unit in units) {
                for (type in unit.allTypes()) {
                    val qn = type.qualifiedName ?: continue
                    put(
                        qn,
                        type.supertypes.mapNotNull { superRef ->
                            superRef.qualifiedName ?: resolveSimpleName(stripGenerics(superRef.name), unit)
                        },
                    )
                }
            }
        }

    private val childrenCount: Map<String, Int> =
        buildMap {
            supertypeQns.forEach { (_, supers) -> supers.forEach { merge(it, 1, Int::plus) } }
        }

    // Package dependency edges: pkg -> set of OTHER packages it imports (internal or external).
    private val efferent: Map<String, Set<String>> =
        buildMap {
            for (unit in units) {
                val deps = unit.imports.map { packageOfImport(it) }.filter { it.isNotEmpty() && it != unit.packageName }
                merge(unit.packageName, deps.toSet()) { a, b -> a + b }
            }
        }

    private val afferent: Map<String, Set<String>> =
        buildMap {
            efferent.forEach { (from, tos) ->
                tos.forEach { to -> if (to in internalPackages) merge(to, setOf(from)) { a, b -> a + b } }
            }
        }

    private val typesByPackage: Map<String, List<TypeDecl>> =
        units
            .groupBy { it.packageName }
            .mapValues { (_, us) -> us.flatMap { it.types } }

    override fun typeByQName(qualifiedName: String): TypeDecl? = typeByQn[qualifiedName]

    override fun directSupertypeQNames(typeQName: String): List<String> = supertypeQns[typeQName].orEmpty()

    /** Name-based until resolution is turned on: report NAME_BASED unless every super resolved in-project. */
    override fun inheritanceResolution(typeQName: String): Resolution {
        val type = typeByQn[typeQName] ?: return Resolution.NAME_BASED
        if (type.supertypes.isEmpty()) return Resolution.RESOLVED
        val edges = type.supertypes.map { it.resolution }
        return Resolution.weakest(edges)
    }

    override fun childrenCountOf(typeQName: String): Int = childrenCount[typeQName] ?: 0

    override fun afferentPackagesOf(pkg: String): Set<String> = afferent[pkg].orEmpty()

    override fun efferentPackagesOf(pkg: String): Set<String> = efferent[pkg].orEmpty()

    override fun typesInPackage(pkg: String): List<TypeDecl> = typesByPackage[pkg].orEmpty()

    /** All distinct package names that hold at least one type (drives the package-metric pass). */
    fun packageNames(): Set<String> = typesByPackage.keys

    /** Resolve a simple supertype name to an in-project qname via same package, then imports. */
    private fun resolveSimpleName(
        simple: String,
        unit: SourceUnit,
    ): String? {
        val samePackage = "${unit.packageName}.$simple"
        if (samePackage in typeByQn) return samePackage
        unit.imports.firstOrNull { it.substringAfterLast('.') == simple }?.let { return it }
        // Last resort: an in-project type with that simple name. Handle ambiguity EXPLICITLY rather than
        // letting singleOrNull() silently return null and drop the edge.
        val candidates = typesBySimpleName[simple].orEmpty().mapNotNull { it.qualifiedName }
        val distinct = candidates.distinct()
        when {
            // (a)/(b) Exactly one candidate qname — unique, or duplicates collapsing to the same qname.
            distinct.size == 1 -> return distinct.single()
            // (c) Genuinely ambiguous: the same-package check above already disambiguates a same-package
            // candidate, so prefer one here only as a defensive tiebreak; otherwise leave it unresolved
            // (same degraded behavior as an unknown external type — the edge stops the DIT walk).
            distinct.size > 1 -> return distinct.firstOrNull { it == samePackage }
        }
        return null // external / unresolved — the DIT walk counts the edge but cannot traverse it
    }

    private fun stripGenerics(name: String): String = name.substringBefore('<').trim()

    /**
     * The package an import targets. A type import `a.b.C` → `a.b`; a wildcard/package import `a.b`
     * stays `a.b`. Heuristic: a final segment starting uppercase is treated as a type name (documented
     * in doc/calibration.md), since standalone name-based imports carry no resolved kind.
     */
    private fun packageOfImport(qname: String): String {
        val last = qname.substringAfterLast('.')
        return if (last.isNotEmpty() && last[0].isUpperCase()) qname.substringBeforeLast('.', "") else qname
    }
}
