package dev.ktrics.module

import java.io.File

/**
 * Maps an absolute source-file path to the module that owns it, and answers "is this package
 * external?" relative to the graph — the question Martin efferent coupling (Ce) turns on.
 * "External" is defined by the module graph, not by guesswork.
 *
 * Source roots are matched longest-first so a nested root (`app/src/main/kotlin`) wins over a
 * shorter overlapping one.
 */
class ModuleResolver(
    val graph: ModuleGraph,
    private val projectRoot: File,
) {
    private data class RootBinding(val module: ModuleNode, val root: File)

    private val rootsLongestFirst: List<RootBinding> =
        graph.modules
            .flatMap { m -> m.srcRoots.map { RootBinding(m, resolve(it)) } }
            .sortedByDescending { it.root.path.length }

    /** The module owning [file], or null when the file lies under no declared source root. */
    fun moduleOf(file: File): ModuleNode? {
        val canonical = file.absoluteFile.normalize()
        return rootsLongestFirst.firstOrNull { binding ->
            canonical.path == binding.root.path || canonical.startsWith(binding.root.path + File.separator)
        }?.module
    }

    fun moduleOf(path: String): ModuleNode? = moduleOf(resolve(path))

    /**
     * Whether [packageName] is produced by no in-graph source module — i.e. it lives in a dependency
     * JAR or the JDK. Requires the set of packages actually declared by project sources, which the
     * engine collects during the IR pass. This keeps "external" graph-defined, never heuristic.
     */
    fun isExternalPackage(
        packageName: String,
        internalPackages: Set<String>,
    ): Boolean = packageName !in internalPackages

    private fun resolve(path: String): File {
        val expanded =
            if (path.startsWith("~")) {
                System.getProperty("user.home") + path.removePrefix("~")
            } else {
                path
            }
        val f = File(expanded)
        return (if (f.isAbsolute) f else File(projectRoot, expanded)).absoluteFile.normalize()
    }
}
