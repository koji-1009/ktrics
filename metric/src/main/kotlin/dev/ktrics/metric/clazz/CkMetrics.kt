package dev.ktrics.metric.clazz

import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.TypeDecl
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Thresholds
import dev.ktrics.metric.TypeMetric

/**
 * The academic CK suite (Chidamber & Kemerer 1994) + cohesion (Hitz & Montazeri 1995), full strength
 * on Java. Written ONCE against `referencedTypes()`/`supertypes()`/`calledSymbols()`:
 * initially name-based, resolved cross-language data once resolution is on — no rewrite.
 */

/** NOM: declared methods, including abstract ones. */
class NumberOfMethods : TypeMetric {
    override val def =
        MetricDef(
            id = "number-of-methods",
            scopeKind = ScopeKind.TYPE,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "—",
            rationale =
                "Count of methods declared on the type (abstract included). A high count is a coarse " +
                    "signal the type has accreted too many responsibilities. Measure-only by default (class-level " +
                    "thresholds are project-specific and noisy as a gate); set one in ktrics.yaml to enforce it.",
            refactorHints = listOf("Split the type along responsibility lines.", "Extract a collaborator object."),
            references = emptyList(),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Double = type.methods.count { !it.isConstructor }.toDouble()
}

/** WMC: Σ cyclomatic complexity over the type's methods (CK 1994). */
class WeightedMethodsPerClass : TypeMetric {
    override val def =
        MetricDef(
            id = "weighted-methods-per-class",
            scopeKind = ScopeKind.TYPE,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "CK 1994",
            rationale =
                "Sum of method cyclomatic complexities — a complexity-weighted size that captures both " +
                    "how many methods a class has and how branchy each is.",
            refactorHints =
                listOf(
                    "Move the most complex methods to a dedicated collaborator.",
                    "Simplify individual methods (see cyclomatic-complexity hints).",
                ),
            references = listOf("Chidamber, S. & Kemerer, C. (1994). A Metrics Suite for OO Design. IEEE TSE."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Double {
        val c = ctx.classifier
        return type.methods.filter { !it.isConstructor }.sumOf { m ->
            val root = m.bodyNode ?: m.node
            // Include `root` itself, matching CyclomaticComplexity — WMC is contractually Σ(per-method
            // cyclomatic), so an expression-bodied decision method (`= if`/`= a && b`) must count its root.
            1 + (sequenceOf(root) + c.descendants(root)).sumOf { c.decisionWeight(it) }
        }.toDouble()
    }
}

/**
 * LCOM4 (Hitz & Montazeri 1995): the number of connected components of the graph whose nodes are
 * methods, with an edge when two methods share a field/property OR one calls the other. 1 component
 * is cohesive; more than 1 suggests the type bundles unrelated responsibilities.
 */
class Lcom4 : TypeMetric {
    override val def =
        MetricDef(
            id = "lcom4",
            scopeKind = ScopeKind.TYPE,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "Hitz & Montazeri 1995",
            rationale =
                "Counts the disconnected clusters of methods (by shared fields and intra-class calls). " +
                    "More than one cluster means the type can likely be split into cohesive pieces.",
            refactorHints =
                listOf(
                    "Split the type so each LCOM4 component becomes its own class.",
                    "If a method neither touches a field nor calls a sibling, consider making it a free/static function.",
                ),
            references = listOf("Hitz, M. & Montazeri, B. (1995). Measuring Coupling and Cohesion in OO Systems."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Double? {
        // Interfaces/annotations have no implementation — every abstract method is its own component,
        // so LCOM4 is meaningless (and pure noise) on them. N/A (dogfooding surfaced this).
        if (type.kind == dev.ktrics.ir.TypeKind.INTERFACE || type.kind == dev.ktrics.ir.TypeKind.ANNOTATION) return null
        val c = ctx.classifier
        val methods = type.methods.filter { !it.modifiers.isStatic && !it.isConstructor }
        if (methods.size <= 1) return methods.size.toDouble()
        val fieldNames = type.fields.map { it.name }.toSet()
        // Each method node is keyed by a (name, parameter-types) signature, NOT the bare simple name, so
        // two overloads sharing a name stay DISTINCT keys instead of collapsing into one. CBO/RFC key by
        // SymbolRef.key, but here the matchable identity is the declaration's own signature: a call site
        // (calledSymbols → SymbolRef with container=null in name-based mode) carries only a simple name,
        // never argument types, so a call cannot be bound to one specific overload. The conservative,
        // correct edge rule is therefore: a call to name `foo` connects to EVERY sibling overload named
        // `foo` (ambiguous but never spuriously merges the overload nodes), while the signature keys keep
        // those overloads from being treated as a single node.
        val signatures = methods.map { signatureKey(it) }
        val signaturesByName = methods.indices.groupBy { methods[it].name }

        val uf = UnionFind(methods.size)
        val accessedFields =
            methods.map { m ->
                c.fieldAccesses(m.bodyNode ?: m.node).intersect(fieldNames)
            }
        // Signatures of sibling overloads reachable from each method, expanding every called simple name
        // to all same-named overloads (call sites lack the arg types needed to pick one).
        val calledSiblingSignatures =
            methods.map { m ->
                c.calledSymbols(m.bodyNode ?: m.node)
                    .flatMap { call -> signaturesByName[call.name].orEmpty() }
                    .map { signatures[it] }
                    .toSet()
            }
        for (i in methods.indices) {
            for (j in i + 1 until methods.size) {
                if (connected(i, j, signatures, accessedFields, calledSiblingSignatures)) uf.union(i, j)
            }
        }
        return uf.componentCount().toDouble()
    }

    /** Stable per-overload key: name + parameter type names, so overloads sharing a name stay distinct. */
    private fun signatureKey(m: FunctionDecl): String = "${m.name}(${m.params.joinToString(",") { it.typeName }})"

    /** Two methods are connected when they share an accessed field OR one calls the other. */
    private fun connected(
        i: Int,
        j: Int,
        signatures: List<String>,
        accessedFields: List<Set<String>>,
        calledSiblingSignatures: List<Set<String>>,
    ): Boolean {
        val shareField = accessedFields[i].intersect(accessedFields[j]).isNotEmpty()
        val callsEachOther = signatures[j] in calledSiblingSignatures[i] || signatures[i] in calledSiblingSignatures[j]
        return shareField || callsEachOther
    }
}

/** CBO: distinct referenced types (CK 1994), resolved when the classpath allows. */
class CouplingBetweenObjects : TypeMetric {
    override val def =
        MetricDef(
            id = "coupling-between-objects",
            scopeKind = ScopeKind.TYPE,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "CK 1994",
            rationale =
                "Number of distinct other types this type references. High coupling makes the type " +
                    "fragile to change elsewhere and hard to test in isolation.",
            refactorHints =
                listOf(
                    "Depend on interfaces, not concretions; inject collaborators.",
                    "Introduce a facade for a cluster of related dependencies.",
                ),
            references = listOf("Chidamber, S. & Kemerer, C. (1994). A Metrics Suite for OO Design."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Double {
        val selfQn = type.qualifiedName
        return ctx.classifier.referencedTypes(type.node)
            .filterNot { ref ->
                // Exclude only the type's own self-reference. With a resolved qualified name we compare
                // precisely, so a same-simple-name type in another package still counts as coupling;
                // a name-based ref can only fall back to a simple-name match (best effort, no resolution).
                if (ref.qualifiedName != null && selfQn != null) {
                    ref.qualifiedName == selfQn
                } else {
                    ref.name == type.name
                }
            }
            .distinctBy { it.key }
            .size.toDouble()
    }

    override fun resolution(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Resolution =
        Resolution.weakest(ctx.classifier.referencedTypes(type.node).map { it.resolution }.ifEmpty { listOf(Resolution.RESOLVED) })
}

/** RFC: methods ∪ called methods (CK 1994), resolved when possible. */
class ResponseForClass : TypeMetric {
    override val def =
        MetricDef(
            id = "response-for-class",
            scopeKind = ScopeKind.TYPE,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "CK 1994",
            rationale =
                "Size of the response set: the type's own methods plus the distinct methods they call. " +
                    "Large RFC means a single message can trigger a wide ripple — harder to test and trace.",
            refactorHints = listOf("Reduce delegation breadth; collapse pass-through methods.", "Split the type."),
            references = listOf("Chidamber, S. & Kemerer, C. (1994). A Metrics Suite for OO Design."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Double {
        val c = ctx.classifier
        val called = type.methods.flatMap { c.calledSymbols(it.bodyNode ?: it.node) }.map { it.key }.toSet()
        return (type.methods.count { !it.isConstructor } + called.size).toDouble()
    }

    override fun resolution(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Resolution {
        val edges = type.methods.flatMap { ctx.classifier.calledSymbols(it.bodyNode ?: it.node) }.map { it.resolution }
        return Resolution.weakest(edges.ifEmpty { listOf(Resolution.RESOLVED) })
    }
}

/** DIT: resolved supertype-chain depth (CK 1994). Kotlin rooted at `Any`; `sealed` hierarchies count. */
class DepthOfInheritanceTree : TypeMetric {
    override val def =
        MetricDef(
            id = "depth-of-inheritance-tree",
            scopeKind = ScopeKind.TYPE,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "CK 1994",
            rationale =
                "How deep the type sits in its inheritance chain. Deep hierarchies concentrate behaviour " +
                    "in ancestors and make the leaf's full contract hard to see.",
            refactorHints = listOf("Prefer composition over deep inheritance.", "Flatten intermediate base classes."),
            references = listOf("Chidamber, S. & Kemerer, C. (1994). A Metrics Suite for OO Design."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Double? {
        val qn = type.qualifiedName ?: return type.supertypes.size.toDouble()
        val index = ctx.index ?: return type.supertypes.size.toDouble()
        return depth(qn, index, emptySet()).toDouble()
    }

    private fun depth(
        qn: String,
        index: dev.ktrics.metric.ProjectIndex,
        path: Set<String>,
    ): Int {
        // Cycle guard is per-ancestor-path, NOT a single set shared across sibling branches: a shared
        // mutable `seen` would let the first branch mark common ancestors visited and make later
        // branches bottom out early, undercounting depth on diamond/interface (DAG) hierarchies.
        if (qn in path) return 0
        val supers = index.directSupertypeQNames(qn)
        if (supers.isEmpty()) return 0
        val nextPath = path + qn
        return 1 + supers.maxOf { depth(it, index, nextPath) }
    }

    override fun resolution(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Resolution? = type.qualifiedName?.let { ctx.index?.inheritanceResolution(it) }
}

/** NOC: resolved incoming supertype edges (CK 1994). */
class NumberOfChildren : TypeMetric {
    override val def =
        MetricDef(
            id = "number-of-children",
            scopeKind = ScopeKind.TYPE,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "CK 1994",
            rationale =
                "Direct subclasses/implementors. Many children means a change to this type ripples widely; " +
                    "it is also a sign the abstraction is central and should be especially stable.",
            refactorHints = listOf("Keep widely-extended base types small and stable.", "Consider sealing the hierarchy."),
            references = listOf("Chidamber, S. & Kemerer, C. (1994). A Metrics Suite for OO Design."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Double? {
        val qn = type.qualifiedName ?: return null
        val index = ctx.index ?: return null
        return index.childrenCountOf(qn).toDouble()
    }

    override fun resolution(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Resolution? = type.qualifiedName?.let { ctx.index?.inheritanceResolution(it) }
}

/** Declaration span of the type. Measure-only by default (no threshold); configurable. */
class ClassLength : TypeMetric {
    override val def =
        MetricDef(
            id = "class-length",
            scopeKind = ScopeKind.TYPE,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "—",
            rationale =
                "Physical line span of the type declaration. Measured for context; not gated by default — " +
                    "set a threshold in ktrics.yaml to enforce one.",
            refactorHints = listOf("Extract cohesive groups of members into collaborators."),
            references = emptyList(),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        type: TypeDecl,
        ctx: MeasureContext,
    ): Double = type.span.lineCount.toDouble()
}

/** Tiny union-find for LCOM4 component counting. */
private class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }

    private fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) {
            parent[r] = parent[parent[r]]
            r = parent[r]
        }
        return r
    }

    fun union(
        a: Int,
        b: Int,
    ) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }

    fun componentCount(): Int = parent.indices.count { find(it) == it }
}
