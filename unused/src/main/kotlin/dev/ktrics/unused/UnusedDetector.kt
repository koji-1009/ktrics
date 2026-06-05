package dev.ktrics.unused

import com.intellij.psi.PsiElement
import dev.ktrics.ir.Lang
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.Span
import dev.ktrics.ir.TypeDecl
import dev.ktrics.ir.Visibility
import dev.ktrics.langapi.NodeClassifier

/** Configuration for unused detection. */
data class UnusedConfig(
    /** Entry-point markers: "main", "@Test", "@ParameterizedTest", and any reflection annotations. */
    val entryPoints: Set<String> = setOf("main", "@Test", "@ParameterizedTest"),
    /** Annotation simple names that keep a symbol alive (from presets + ignore-annotations). */
    val keepAliveAnnotations: Set<String> = emptySet(),
    /** Globs for generated trees skipped by default. */
    val generatedGlobs: List<String> = listOf("build/generated/", "target/generated-sources/"),
    /**
     * Source-set roots marking test trees. Declarations here are still traversed (a `@Test` keeps
     * production code alive) but are NOT reported as unused unless `--include-tests` clears this.
     * Anchored to `src/<set>/` so a production package literally named `test` isn't mistaken for tests.
     */
    val testGlobs: List<String> = listOf("/src/test/", "/src/androidTest/", "/src/integrationTest/", "/src/testFixtures/"),
)

/** One unreachable public-API declaration. */
data class UnusedSymbol(
    val key: String,
    val displayName: String,
    val kind: String,
    val visibility: Visibility,
    val file: String,
    val span: Span,
    val lang: Lang,
    /** True for a file-level declaration; only these are eligible for `--apply` deletion. */
    val topLevel: Boolean,
)

/** Result of a reachability sweep, with the confidence of the whole analysis. */
data class UnusedReport(
    val unused: List<UnusedSymbol>,
    val resolution: Resolution,
    val rootCount: Int,
    val consideredCount: Int,
)

/**
 * Public-API reachability via BFS over the reference graph. Spans module-dependency edges: a
 * symbol used only by a downstream module is reachable, because all modules' units are analyzed
 * together over the shared symbol space. Resolution-backed when the classpath resolves edges,
 * name-based otherwise — and the report stamps which, gating destructive `--apply`.
 *
 * Roots: `main`; JUnit `@Test`/`@ParameterizedTest`; configured reflection annotations; framework
 * stereotypes via presets. Visibility considered: Java public/protected; Kotlin public + internal.
 * Private is skipped (the compiler/IDE already covers it). Generated trees are skipped by default.
 *
 * Edges: call references and type-position references, a resolved call's CONTAINER (so a call keeps
 * its owner object/class alive), value-level name reads ([NodeClassifier.referencedNames] — property
 * reads and object qualifiers sit in no call/type position), and an implicit member → container edge
 * (using a member implies using its enclosing type).
 */
class UnusedDetector(
    private val units: List<SourceUnit>,
    private val classifierFor: (Lang) -> NodeClassifier,
    private val config: UnusedConfig = UnusedConfig(),
) {
    private data class Decl(
        val key: String,
        val displayName: String,
        val kind: String,
        val visibility: Visibility,
        val annotations: List<String>,
        val file: String,
        val span: Span,
        val lang: Lang,
        val outgoing: Set<String>,
        /** Weakest resolution across this decl's outgoing call/type edges; null when it has none. */
        val edgeResolution: Resolution?,
        val isMain: Boolean = false,
        /**
         * True for a member that OVERRIDES a supertype member. An override of an out-of-project
         * supertype (Runnable.run, equals/hashCode, a framework callback) is reachable via the
         * runtime/framework even with no in-project caller, so it is seeded as a root — never reported
         * unused. Always false for types/properties.
         */
        val isOverride: Boolean = false,
        val topLevel: Boolean = false,
        /**
         * False for declarations that participate in reachability but are never reported: type-level
         * fields are graph nodes (an `Owner.CONST` read keeps `Owner` alive through them) while the
         * reported surface stays what it has always been — top-level properties only.
         */
        val reportable: Boolean = true,
    )

    fun detect(): UnusedReport {
        val decls = collectDeclarations()
        val byKey = decls.associateBy { it.key }
        val bySimpleName = decls.groupBy { it.key.substringAfterLast('.') }

        // A keep-alive annotation on a TYPE covers its members too (a @Serializable/@Entity class is
        // touched reflectively as a whole), so members of kept types are seeded as roots and never
        // reported — the dartrics annotation-propagation rule.
        val keptTypeKeys = decls.filterTo(ArrayList()) { it.kind !in MEMBER_KINDS && isKeptAlive(it) }.mapTo(HashSet()) { it.key }

        // Roots: main, entry/keep-alive annotated declarations, members of kept-alive types.
        val roots = decls.filter { isRoot(it) || isMemberOfKeptType(it, keptTypeKeys) }
        val reachable = bfs(roots, byKey, bySimpleName)

        val unused =
            decls.filter { isReportedUnused(it, reachable, keptTypeKeys) }
                .map { UnusedSymbol(it.key, it.displayName, it.kind, it.visibility, it.file, it.span, it.lang, it.topLevel) }

        return UnusedReport(
            unused = unused.sortedWith(compareBy({ it.file }, { it.span.startLine })),
            resolution = resolutionOf(decls),
            rootCount = roots.size,
            consideredCount = decls.count { it.reportable && isReportableSurface(it) },
        )
    }

    private fun collectDeclarations(): List<Decl> =
        buildList {
            for (unit in units) {
                val classifier = classifierFor(unit.lang)
                unit.topLevelFns.forEach { fn ->
                    val edges = edgesOf(classifier, fn.node)
                    add(
                        Decl(
                            key = "${unit.packageName}.${fn.name}",
                            displayName = "${fn.name}()",
                            kind = "function",
                            visibility = fn.modifiers.visibility,
                            annotations = fn.annotations,
                            file = unit.path,
                            span = fn.span,
                            lang = unit.lang,
                            outgoing = edges.outgoing,
                            edgeResolution = edges.resolution,
                            isMain = fn.name == "main",
                            isOverride = fn.modifiers.isOverride,
                            topLevel = true,
                        ),
                    )
                }
                unit.topLevelProps.forEach { p ->
                    val edges = edgesOf(classifier, p.node)
                    add(
                        // A property is not seeded as an override root: isMain/isOverride stay false.
                        Decl(
                            key = "${unit.packageName}.${p.name}",
                            displayName = p.name,
                            kind = "property",
                            visibility = p.modifiers.visibility,
                            annotations = p.annotations,
                            file = unit.path,
                            span = p.span,
                            lang = unit.lang,
                            outgoing = edges.outgoing,
                            edgeResolution = edges.resolution,
                            topLevel = true,
                        ),
                    )
                }
                unit.types.forEach { type -> addType(type, unit, classifier, topLevel = true) }
            }
        }

    private fun MutableList<Decl>.addType(
        type: TypeDecl,
        unit: SourceUnit,
        classifier: NodeClassifier,
        topLevel: Boolean,
        containerKey: String? = null,
    ) {
        val qn = type.qualifiedName ?: "${unit.packageName}.${type.name}"
        val typeEdges = edgesOf(classifier, type.node)
        add(
            // A type itself is never an "override" root.
            Decl(
                key = qn,
                displayName = type.name,
                kind = type.kind.name.lowercase(),
                visibility = type.modifiers.visibility,
                annotations = type.annotations,
                file = unit.path,
                span = type.span,
                lang = unit.lang,
                outgoing = typeEdges.outgoing,
                edgeResolution = typeEdges.resolution,
                topLevel = topLevel,
            ).reaching(containerKey),
        )
        type.methods.forEach { m ->
            val edges = edgesOf(classifier, m.node)
            add(
                Decl(
                    key = "$qn.${m.name}",
                    displayName = "${type.name}.${m.name}()",
                    kind = "method",
                    visibility = m.modifiers.visibility,
                    annotations = m.annotations,
                    file = unit.path,
                    span = m.span,
                    lang = unit.lang,
                    outgoing = edges.outgoing,
                    edgeResolution = edges.resolution,
                    isMain = m.name == "main",
                    isOverride = m.modifiers.isOverride,
                ).reaching(qn),
            )
        }
        type.fields.forEach { f ->
            val edges = edgesOf(classifier, f.node)
            add(
                // Reachability node only (reportable = false): a `Owner.CONST` read must keep the
                // companion/object alive, but type-level fields are not part of the reported surface.
                Decl(
                    key = "$qn.${f.name}",
                    displayName = "${type.name}.${f.name}",
                    kind = "property",
                    visibility = f.modifiers.visibility,
                    annotations = f.annotations,
                    file = unit.path,
                    span = f.span,
                    lang = unit.lang,
                    outgoing = edges.outgoing,
                    edgeResolution = edges.resolution,
                    reportable = false,
                ).reaching(qn),
            )
        }
        type.nested.forEach { addType(it, unit, classifier, topLevel = false, containerKey = qn) }
    }

    /**
     * Using a member implies using its container: a member decl carries an explicit exact-key edge to
     * [containerKey], so reaching `DaemonCli.runForeground` marks object `DaemonCli` reachable even
     * when nothing references the container by name (e.g. a member imported directly).
     */
    private fun Decl.reaching(containerKey: String?): Decl = if (containerKey == null) this else copy(outgoing = outgoing + containerKey)

    /** The outgoing edge set of one declaration scope, with the weakest call/type-edge resolution. */
    private data class Edges(val outgoing: Set<String>, val resolution: Resolution?)

    private fun edgesOf(
        classifier: NodeClassifier,
        node: PsiElement,
    ): Edges {
        val called = classifier.calledSymbols(node)
        val types = classifier.referencedTypes(node)
        // Reachability is DELIBERATELY simple-name (a deliberate false-NEGATIVE bias, as the sibling
        // tool does): a method invoked only through an interface resolves to the interface member, not
        // the concrete override, so precise (resolved-key) edges would report that override as dead —
        // a false positive that risks deleting live code. Over-connecting same-named symbols instead
        // errs toward keeping live code; the resolution STAMP below still gates the destructive --apply.
        //
        // Three channels feed the edge set:
        //  - bare call/type names — the simple-name over-connection described above;
        //  - call CONTAINERS — a resolved call also names its owner, so `DaemonCli.runForeground()`
        //    keeps object `DaemonCli` alive, and a resolved constructor (named `<init>`, which matches
        //    no declaration key) reaches its class only through this channel;
        //  - referencedNames — value-level reads (property/field reads, object/companion qualifiers)
        //    that are neither calls nor type positions. Name-based by construction and only ever ADDS
        //    reachability (it cannot cause a wrong deletion), so it does not feed the resolution stamp.
        val outgoing =
            (called.map { it.name } + called.mapNotNull { it.container } + types.map { it.name } + classifier.referencedNames(node))
                .toSet()
        val edgeResolutions = called.map { it.resolution } + types.map { it.resolution }
        return Edges(outgoing, if (edgeResolutions.isEmpty()) null else Resolution.weakest(edgeResolutions))
    }

    private fun bfs(
        roots: List<Decl>,
        byKey: Map<String, Decl>,
        bySimple: Map<String, List<Decl>>,
    ): Set<String> {
        val reachable = HashSet<String>()
        val queue = ArrayDeque<Decl>()
        roots.forEach { if (reachable.add(it.key)) queue.add(it) }
        while (queue.isNotEmpty()) {
            val decl = queue.removeFirst()
            val targets = decl.outgoing.flatMap { targetsOf(it, byKey, bySimple) }
            for (t in targets) {
                if (reachable.add(t.key)) queue.add(t)
            }
        }
        return reachable
    }

    /**
     * Exact (resolved, qualified) key match first; else fall back to the SIMPLE name — a resolved key
     * is `pkg.Owner.member`, and the simple-name index is keyed by the bare member, so we must strip
     * to the last segment. Reachability errs toward over-connecting (a missed edge would wrongly
     * report live code as unused; `--apply` is gated on RESOLVED). The simple-name fallback
     * INTENTIONALLY over-approximates: a qualified off-project ref like `kotlin.run` here marks a
     * project `run` reachable. That under-reports dead code but never flags live code — the safe
     * direction for a deletion tool — so it stays as-is.
     */
    private fun targetsOf(
        ref: String,
        byKey: Map<String, Decl>,
        bySimple: Map<String, List<Decl>>,
    ): List<Decl> = byKey[ref]?.let { listOf(it) } ?: bySimple[ref.substringAfterLast('.')].orEmpty()

    /** The full reported-as-unused predicate: reportable surface, not excluded, not kept, not reached. */
    private fun isReportedUnused(
        d: Decl,
        reachable: Set<String>,
        keptTypeKeys: Set<String>,
    ): Boolean =
        d.reportable &&
            isReportableSurface(d) &&
            !isGenerated(d.file) &&
            !isTest(d.file) &&
            !isKeptAlive(d) &&
            !isMemberOfKeptType(d, keptTypeKeys) &&
            d.key !in reachable &&
            !d.isMain

    private fun isRoot(d: Decl): Boolean {
        if (d.isMain && "main" in config.entryPoints) return true
        // An override of an out-of-project supertype member (Runnable.run, equals/hashCode, a framework
        // callback) is invoked via the runtime/framework, not an in-project caller, so it would be
        // wrongly reported unused. Seed every override as a root — this strictly REDUCES false positives.
        if (d.isOverride) return true
        return d.annotations.any { ann ->
            "@$ann" in config.entryPoints || ann in config.keepAliveAnnotations
        }
    }

    private fun isKeptAlive(d: Decl): Boolean = d.annotations.any { it in config.keepAliveAnnotations }

    /** True when [d]'s key sits under a kept-alive type's key (`pkg.Type` covers `pkg.Type.member`). */
    private fun isMemberOfKeptType(
        d: Decl,
        keptTypeKeys: Set<String>,
    ): Boolean = keptTypeKeys.any { key -> d.key.length > key.length && d.key.startsWith(key) && d.key[key.length] == '.' }

    /**
     * The visibility surface the detector reports on, by language: Java public/protected, Kotlin
     * public/internal. (Kotlin `protected` is reachable only from subclasses — possibly out-of-project
     * — so reporting it as unused would over-report; Java has no `internal`.)
     */
    private fun isReportableSurface(d: Decl): Boolean =
        when (d.lang) {
            Lang.KOTLIN -> d.visibility == Visibility.PUBLIC || d.visibility == Visibility.INTERNAL
            Lang.JAVA -> d.visibility == Visibility.PUBLIC || d.visibility == Visibility.PROTECTED
        }

    private fun isGenerated(file: String): Boolean {
        val normalized = file.replace('\\', '/')
        return config.generatedGlobs.any { normalized.contains(it) }
    }

    private fun isTest(file: String): Boolean {
        val normalized = "/" + file.replace('\\', '/')
        return config.testGlobs.any { normalized.contains(it) }
    }

    /**
     * Confidence of the whole sweep, gating destructive `--apply`. It must reflect EVERY edge the
     * BFS actually traversed — both `calledSymbols` and `referencedTypes`, across every declaration
     * (not just types) — so a name-based call edge can't be masked by resolved type edges and
     * wrongly stamp the report RESOLVED. Name-based until resolution is turned on.
     */
    private fun resolutionOf(decls: List<Decl>): Resolution {
        val edges = decls.mapNotNull { it.edgeResolution }
        return Resolution.weakest(edges.ifEmpty { listOf(Resolution.NAME_BASED) })
    }

    private companion object {
        /** Declaration kinds that are members, not types — the complement defines "type" for keep-alive propagation. */
        val MEMBER_KINDS = setOf("function", "method", "property")
    }
}
