package dev.ktrics.frontend

import dev.ktrics.ir.Lang
import dev.ktrics.module.ModuleGraph
import dev.ktrics.module.ModuleNode
import dev.ktrics.module.SourceFilter
import java.io.File

/**
 * Resolves a [ModuleGraph]'s declared, project-relative paths into concrete source files and
 * classpath entries the platform session can consume. Platform-free on purpose: keeps the path
 * arithmetic unit-testable apart from the (heavy, version-locked) Analysis API bootstrap.
 */
class SessionPaths(
    val graph: ModuleGraph,
    val projectRoot: File,
) {
    data class ResolvedModule(
        val node: ModuleNode,
        val kotlinFiles: List<File>,
        val javaFiles: List<File>,
        val classpath: List<File>,
    ) {
        val allSourceFiles: List<File> get() = kotlinFiles + javaFiles
    }

    fun resolveAll(): List<ResolvedModule> = graph.modules.map { resolve(it) }

    fun resolve(node: ModuleNode): ResolvedModule {
        val roots = node.srcRoots.map { absolute(it) }
        val kotlin = roots.flatMap { collect(it, Lang.KOTLIN) }
        val java = roots.flatMap { collect(it, Lang.JAVA) }
        val cp = node.classpath.map { absolute(it) }.filter { it.exists() }
        return ResolvedModule(node, kotlin.sorted(), java.sorted(), cp)
    }

    private fun collect(
        root: File,
        lang: Lang,
    ): List<File> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            // Lang.fromExtension is the one extension → language mapping, shared with the IR.
            .filter { it.isFile && Lang.fromExtension(it.extension) == lang && !SourceFilter.isBuildScript(it.name) }
            .toList()
    }

    private fun absolute(path: String): File {
        val expanded = if (path.startsWith("~")) System.getProperty("user.home") + path.removePrefix("~") else path
        val f = File(expanded)
        return (if (f.isAbsolute) f else File(projectRoot, expanded)).absoluteFile.normalize()
    }

    private fun List<File>.sorted(): List<File> = sortedBy { it.path }
}

/**
 * The ONE absolute-path → project-relative mapping every frontend lowers report paths through.
 * '/'-separated everywhere: report paths must compare equal to git output (always '/') and to
 * config globs written with '/' — on Windows the native separator would silently match neither.
 */
fun projectRelativePath(
    absolutePath: String,
    projectRoot: File,
): String {
    val rootPath = projectRoot.absolutePath
    val rel =
        if (absolutePath.startsWith(rootPath)) {
            absolutePath.removePrefix(rootPath).trimStart(File.separatorChar)
        } else {
            absolutePath
        }
    return rel.replace(File.separatorChar, '/')
}
