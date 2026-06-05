package dev.ktrics.engine

import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths

/**
 * Compiled `exclude:` globs from ktrics.yaml, matched against project-relative paths. The ONE
 * definition of what `exclude:` means, shared by metric measurement (AnalysisEngine) and the
 * unused report — so a file excluded from violations cannot still be reported as unused.
 * Excluded files still feed the index/graph (they remain resolution targets and their references
 * keep other declarations alive); they are just not reported on.
 */
class ExcludeFilter(globs: List<String>) {
    private val matchers: List<PathMatcher> = globs.flatMap(::matchersFor)

    fun excludes(relativePath: String): Boolean {
        if (matchers.isEmpty()) return false
        val path = runCatching { Paths.get(relativePath) }.getOrNull() ?: return false
        return matchers.any { it.matches(path) }
    }

    /**
     * Path matchers for one configured glob. A leading globstar segment in a Java glob requires a
     * parent path component, so a pattern intended to match `build` anywhere does NOT match a
     * top-level `build/classes/Foo.kt`; we also register the form with that leading globstar segment
     * stripped so both top-level and nested paths match.
     */
    private fun matchersFor(glob: String): List<PathMatcher> {
        val fs = FileSystems.getDefault()
        val leadingGlobstar = "**/"
        val variants =
            buildList {
                add(glob)
                if (glob.startsWith(leadingGlobstar)) add(glob.removePrefix(leadingGlobstar))
            }
        return variants.mapNotNull { runCatching { fs.getPathMatcher("glob:$it") }.getOrNull() }
    }
}
