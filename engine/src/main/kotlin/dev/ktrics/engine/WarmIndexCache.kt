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
    private val entries = HashMap<String, WarmIndex>()

    @Synchronized
    fun get(
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

    /** Drops every warm entry (daemon shutdown). */
    @Synchronized
    fun clear() {
        entries.values.forEach { it.session.runCatching { close() } }
        entries.clear()
    }

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
