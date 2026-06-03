package dev.ktrics.module

import kotlinx.serialization.Serializable

/**
 * The declarative module graph.
 *
 * A real Java/Kotlin project is always several modules with dependency edges; feeding the platform
 * one undifferentiated source root is the wrong use of it. This model drives session construction,
 * cross-module resolution, Martin package boundaries, and cross-module unused detection. It is
 * intentionally platform-free — the KaModule *translation* lives in :frontend.
 *
 * Sourcing the graph is staged: v1 declares it by hand (ktrics.yaml / --module);
 * v2 auto-derives it via the Gradle Tooling API / Maven. Modeling modules is v1; auto-discovering
 * them is v2 — these are not conflated.
 */
@Serializable
data class ModuleGraph(
    val modules: List<ModuleNode>,
) {
    // Declared BEFORE init so the cycle check (which uses it) sees an initialized map.
    private val byName: Map<String, ModuleNode> = modules.associateBy { it.name }

    init {
        val names = modules.map { it.name }
        require(names.toSet().size == names.size) {
            "Duplicate module names in graph: ${names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
        }
        val known = names.toSet()
        modules.forEach { m ->
            m.dependsOn.forEach { dep ->
                require(dep in known) { "Module '${m.name}' depends on unknown module '$dep'" }
            }
        }
        require(!hasCycle()) { "Module dependency graph has a cycle: ${cyclePath()}" }
    }

    fun module(name: String): ModuleNode? = byName[name]

    /** Direct dependency nodes of [name], in declaration order. */
    fun directDependencies(name: String): List<ModuleNode> = byName[name]?.dependsOn?.mapNotNull { byName[it] } ?: emptyList()

    /** Transitive closure of dependencies of [name] (excluding itself), deepest-first stable order. */
    fun transitiveDependencies(name: String): List<ModuleNode> {
        // Post-order DFS: a dependency is appended only AFTER its own dependencies, so the deepest leaves
        // come first and every module precedes the modules that depend on it (a valid topological order,
        // e.g. for classpath assembly). The graph is acyclic (checked in init), and `result` doubles as
        // the visited set so a diamond dependency is appended exactly once.
        val result = LinkedHashSet<String>()

        fun visit(n: String) {
            byName[n]?.dependsOn?.forEach { d ->
                if (d !in result) {
                    visit(d)
                    result.add(d)
                }
            }
        }
        visit(name)
        return result.mapNotNull { byName[it] }
    }

    /** Modules that depend (transitively) on [name] — the downstream invalidation set. */
    fun downstreamOf(name: String): List<ModuleNode> =
        modules.filter { it.name != name && name in transitiveDependencies(it.name).map { d -> d.name } }

    private fun hasCycle(): Boolean = cyclePath() != null

    /** Returns a cyclic path if one exists, else null. */
    fun cyclePath(): List<String>? {
        val state = HashMap<String, Int>() // 0=unvisited,1=in-stack,2=done
        val stack = ArrayList<String>()

        fun dfs(n: String): List<String>? {
            state[n] = 1
            stack.add(n)
            for (d in byName[n]?.dependsOn.orEmpty()) {
                when (state[d] ?: 0) {
                    1 -> return stack.subList(stack.indexOf(d), stack.size).toList() + d
                    0 -> dfs(d)?.let { return it }
                    else -> {}
                }
            }
            stack.removeAt(stack.size - 1)
            state[n] = 2
            return null
        }
        for (m in modules) if ((state[m.name] ?: 0) == 0) dfs(m.name)?.let { return it }
        return null
    }

    companion object {
        /** A flat single-module fallback when the graph is undeclared (still legal, just coarse). */
        fun singleModule(
            srcRoots: List<String>,
            classpath: List<String> = emptyList(),
        ): ModuleGraph = ModuleGraph(listOf(ModuleNode("root", srcRoots, classpath, emptyList())))
    }
}

/**
 * One module: its source roots, its binary (JAR) dependencies, and project edges to the modules it
 * depends on. Mirrors the Analysis API's `KaSourceModule` + `KaLibraryModule` + dependency edges.
 */
@Serializable
data class ModuleNode(
    val name: String,
    val srcRoots: List<String>,
    /** Per-module binary deps; an external edge unresolved here degrades to name-based. */
    val classpath: List<String> = emptyList(),
    /** Project edges — enable cross-module resolution both directions. */
    val dependsOn: List<String> = emptyList(),
)
