package dev.ktrics.engine

import dev.ktrics.frontend.KtricsSession
import dev.ktrics.frontend.StandaloneSessionFactory
import dev.ktrics.frontend.java.JavaFrontend
import dev.ktrics.frontend.kotlin.KotlinFrontend
import dev.ktrics.ir.SourceUnit
import dev.ktrics.module.ModuleGraph
import dev.ktrics.module.SessionPathsKey
import java.io.File

/** The warm artifacts held in memory across loop iterations: session, lowered IR, project index. */
class WarmIndex(
    val session: KtricsSession,
    val units: List<SourceUnit>,
    val index: ProjectIndexImpl,
    /** path → "mtime:size" for every source AND classpath file under the graph (see [WarmIndexCache.fingerprint]). */
    val fingerprint: Map<String, String>,
)

/**
 * Process-wide warm cache. The daemon keys its in-memory session by (project root +
 * module graph + classpath + config hash) so differing invocations never share an index. Re-parsing
 * is avoided when no source file changed; on any change the entry is rebuilt.
 *
 * Invalidation is currently whole-project (rebuild on any source change); it can be refined later to
 * dependency-aware invalidation along the module graph — editing a type re-resolves only the files,
 * in this and downstream modules, that reference it; AST-only metrics stay strictly per-file.
 */
object WarmIndexCache {
    /** Bounded LRU: a long-lived daemon serving many distinct (root, config) keys must not pin every
     *  session forever — each entry holds a full IntelliJ application + PSI. Eviction closes the
     *  session; safe because EVERY consumer reads under this object's monitor ([withWarm]/analyze).
     *  Internal-mutable so the eviction path is testable without booting nine real sessions. */
    internal var maxEntries = DEFAULT_MAX_ENTRIES

    private val entries =
        object : LinkedHashMap<String, WarmIndex>(DEFAULT_MAX_ENTRIES, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, WarmIndex>): Boolean {
                if (size <= maxEntries) return false
                eldest.value.session.runCatching { close() }
                return true
            }
        }

    /** Prefer [withWarm]: a bare get releases the monitor after the lookup (see withWarm's contract). */
    @Synchronized
    internal fun get(
        projectRoot: File,
        graph: ModuleGraph,
        configHash: String,
        resolved: Boolean = false,
    ): WarmIndex {
        val key = key(projectRoot, graph, "$configHash|resolved=$resolved")
        val current = fingerprint(projectRoot, graph)
        val cached = entries[key]
        if (cached != null && cached.fingerprint == current) return cached
        cached?.session?.runCatching { close() }
        val rebuilt = build(projectRoot, graph, current, resolved)
        entries[key] = rebuilt
        return rebuilt
    }

    /**
     * Runs [block] over the warm index while HOLDING this object's monitor — the same monitor
     * `AnalysisEngine.analyze` serializes on. Every consumer that traverses PSI or enters
     * `analyze {}` (unused sweep, call graph, inspect) MUST go through here: a bare [get] releases
     * the lock after the lookup, so a concurrent request could rebuild the cache and dispose the
     * session mid-traversal (use-after-dispose), or race the non-thread-safe Analysis API.
     */
    @Synchronized
    fun <T> withWarm(
        projectRoot: File,
        graph: ModuleGraph,
        configHash: String,
        resolved: Boolean = false,
        block: (WarmIndex) -> T,
    ): T = block(get(projectRoot, graph, configHash, resolved))

    /**
     * Closes and forgets every entry whose project root is [dir] or sits below it. `regression`
     * analyzes throwaway temp worktrees whose keys never repeat — without eviction each run would
     * permanently leak two live sessions rooted at deleted directories.
     */
    @Synchronized
    fun evict(dir: File) {
        val path = dir.absolutePath
        // Keys start with "<rootPath>|": match the exact root and any root below [dir] — but never a
        // sibling sharing the name prefix ("…/rev-1" must not evict "…/rev-10").
        val stale = entries.keys.filter { it.startsWith("$path|") || it.startsWith(path + File.separator) }
        stale.forEach { key -> entries.remove(key)?.session?.runCatching { close() } }
    }

    /** Drops every warm entry (daemon shutdown). */
    @Synchronized
    fun clear() {
        entries.values.forEach { it.session.runCatching { close() } }
        entries.clear()
    }

    private const val DEFAULT_MAX_ENTRIES = 8
    private const val LOAD_FACTOR = 0.75f

    private fun build(
        projectRoot: File,
        graph: ModuleGraph,
        fingerprint: Map<String, String>,
        resolved: Boolean,
    ): WarmIndex {
        val kotlinFrontend = KotlinFrontend(projectRoot, resolved)
        val javaFrontend = JavaFrontend(projectRoot, resolved)
        val session = StandaloneSessionFactory.build(graph, projectRoot)
        val units =
            buildList {
                session.ktFiles.forEach { add(kotlinFrontend.lower(it)) }
                session.javaFiles.forEach { add(javaFrontend.lower(it)) }
            }
        return WarmIndex(session, units, ProjectIndexImpl(units), fingerprint)
    }

    /**
     * Path → "mtime:size" for every source AND classpath file under the graph; identity says "nothing
     * changed". Includes file SIZE alongside mtime so a same-second edit (coarse filesystem mtime
     * granularity is often 1s) that changes the content length is still detected, and includes classpath
     * (binary dependency) files so swapping/rebuilding a JAR invalidates the cached, now-stale session.
     */
    private fun fingerprint(
        projectRoot: File,
        graph: ModuleGraph,
    ): Map<String, String> =
        (SessionPathsKey.sourceFiles(graph, projectRoot) + SessionPathsKey.classpathFiles(graph, projectRoot))
            .associate { it.path to "${it.lastModified()}:${it.length()}" }

    private fun key(
        projectRoot: File,
        graph: ModuleGraph,
        configHash: String,
    ): String =
        "${projectRoot.absolutePath}|" +
            graph.modules.joinToString(";") { "${it.name}:${it.srcRoots}:${it.classpath}:${it.dependsOn}" } +
            "|$configHash"
}
