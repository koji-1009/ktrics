package dev.ktrics.unused

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
        val isMain: Boolean,
        /**
         * True for a member that OVERRIDES a supertype member. An override of an out-of-project
         * supertype (Runnable.run, equals/hashCode, a framework callback) is reachable via the
         * runtime/framework even with no in-project caller, so it is seeded as a root — never reported
         * unused. Always false for types/properties.
         */
        val isOverride: Boolean,
        val topLevel: Boolean,
        val outgoing: Set<String>,
        /** Weakest resolution across this decl's outgoing edges; null when it has none. */
        val edgeResolution: Resolution?,
    )

    fun detect(): UnusedReport {
        val decls = collectDeclarations()
        val byKey = decls.associateBy { it.key }
        val bySimpleName = decls.groupBy { it.key.substringAfterLast('.') }

        // Roots: main, entry/keep-alive annotated declarations.
        val roots = decls.filter { isRoot(it) }
        val reachable = bfs(roots, byKey, bySimpleName)

        val unused =
            decls.filter { d ->
                isReportableSurface(d) &&
                    !isGenerated(d.file) &&
                    !isTest(d.file) &&
                    !isKeptAlive(d) &&
                    d.key !in reachable &&
                    !d.isMain
            }.map { UnusedSymbol(it.key, it.displayName, it.kind, it.visibility, it.file, it.span, it.lang, it.topLevel) }

        return UnusedReport(
            unused = unused.sortedWith(compareBy({ it.file }, { it.span.startLine })),
            resolution = resolutionOf(decls),
            rootCount = roots.size,
            consideredCount = decls.count { isReportableSurface(it) },
        )
    }

    private fun collectDeclarations(): List<Decl> =
        buildList {
            for (unit in units) {
                val classifier = classifierFor(unit.lang)
                unit.topLevelFns.forEach { fn ->
                    val key = "${unit.packageName}.${fn.name}"
                    add(
                        declOf(
                            key,
                            "${fn.name}()",
                            "function",
                            fn.modifiers.visibility,
                            fn.annotations,
                            unit,
                            fn.span,
                            classifier,
                            fn.node,
                            fn.name == "main",
                            fn.modifiers.isOverride,
                            topLevel = true,
                        ),
                    )
                }
                unit.topLevelProps.forEach { p ->
                    add(
                        declOf(
                            "${unit.packageName}.${p.name}",
                            p.name,
                            "property",
                            p.modifiers.visibility,
                            p.annotations,
                            unit,
                            p.span,
                            classifier,
                            p.node,
                            false,
                            isOverride = false, // a property is not seeded as an override root
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
    ) {
        val qn = type.qualifiedName ?: "${unit.packageName}.${type.name}"
        add(
            declOf(
                qn,
                type.name,
                type.kind.name.lowercase(),
                type.modifiers.visibility,
                type.annotations,
                unit,
                type.span,
                classifier,
                type.node,
                false,
                isOverride = false, // a type itself is never an "override" root
                topLevel,
            ),
        )
        type.methods.forEach { m ->
            add(
                declOf(
                    "$qn.${m.name}",
                    "${type.name}.${m.name}()",
                    "method",
                    m.modifiers.visibility,
                    m.annotations,
                    unit,
                    m.span,
                    classifier,
                    m.node,
                    m.name == "main",
                    m.modifiers.isOverride,
                    topLevel = false,
                ),
            )
        }
        type.nested.forEach { addType(it, unit, classifier, topLevel = false) }
    }

    private fun declOf(
        key: String,
        display: String,
        kind: String,
        visibility: Visibility,
        annotations: List<String>,
        unit: SourceUnit,
        span: Span,
        classifier: NodeClassifier,
        node: com.intellij.psi.PsiElement,
        isMain: Boolean,
        isOverride: Boolean,
        topLevel: Boolean,
    ): Decl {
        val called = classifier.calledSymbols(node)
        val types = classifier.referencedTypes(node)
        // Reachability is DELIBERATELY simple-name (a deliberate false-NEGATIVE bias, as the sibling
        // tool does): a method invoked only through an interface resolves to the interface member, not
        // the concrete override, so precise (resolved-key) edges would report that override as dead —
        // a false positive that risks deleting live code. Over-connecting same-named symbols instead
        // errs toward keeping live code; the resolution STAMP below still gates the destructive --apply.
        val outgoing = (called.map { it.name } + types.map { it.name }).toSet()
        val edgeResolutions = called.map { it.resolution } + types.map { it.resolution }
        val edgeResolution = if (edgeResolutions.isEmpty()) null else Resolution.weakest(edgeResolutions)
        return Decl(key, display, kind, visibility, annotations, unit.path, span, unit.lang, isMain, isOverride, topLevel, outgoing, edgeResolution)
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
            for (ref in decl.outgoing) {
                // Exact (resolved, qualified) key match first; else fall back to the SIMPLE name — a
                // resolved key is `pkg.Owner.member`, and the simple-name index is keyed by the bare
                // member, so we must strip to the last segment. Reachability errs toward over-connecting
                // (a missed edge would wrongly report live code as unused; `--apply` is gated on RESOLVED).
                // The simple-name fallback INTENTIONALLY over-approximates: a qualified off-project ref
                // like `kotlin.run` here marks a project `run` reachable. That under-reports dead code but
                // never flags live code — the safe direction for a deletion tool — so it stays as-is.
                val targets = byKey[ref]?.let { listOf(it) } ?: bySimple[ref.substringAfterLast('.')].orEmpty()
                for (t in targets) if (reachable.add(t.key)) queue.add(t)
            }
        }
        return reachable
    }

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
}
