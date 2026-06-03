package dev.ktrics.module

import java.io.File

/**
 * Platform-free enumeration of the source files a [ModuleGraph] covers, used to fingerprint the
 * warm cache without pulling in the frontend. Mirrors the path arithmetic the session uses.
 */
object SessionPathsKey {
    fun sourceFiles(
        graph: ModuleGraph,
        projectRoot: File,
    ): List<File> =
        graph.modules.asSequence()
            .flatMap { it.srcRoots.asSequence() }
            .map { absolute(projectRoot, it) }
            .filter { it.exists() }
            .flatMap { root -> root.walkTopDown().filter { SourceFilter.isAnalyzable(it) } }
            .distinct()
            .toList()

    /**
     * Classpath (binary dependency) files the graph references, resolved to absolute paths. Included in
     * the warm-cache fingerprint so a rebuilt/swapped dependency JAR — which changes resolution but no
     * source file — still invalidates the cached session.
     */
    fun classpathFiles(
        graph: ModuleGraph,
        projectRoot: File,
    ): List<File> =
        graph.modules.asSequence()
            .flatMap { it.classpath.asSequence() }
            .map { absolute(projectRoot, it) }
            .filter { it.exists() }
            .distinct()
            .toList()

    private fun absolute(
        projectRoot: File,
        path: String,
    ): File {
        val expanded = if (path.startsWith("~")) System.getProperty("user.home") + path.removePrefix("~") else path
        val f = File(expanded)
        return (if (f.isAbsolute) f else File(projectRoot, expanded)).absoluteFile.normalize()
    }
}
