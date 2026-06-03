package dev.ktrics.module

import java.io.File

/**
 * Decides which files under a source root are real analysis targets. Build scripts are never analysis
 * targets (they have no package and are tooling config, not the codebase under review) — analyzing
 * them produces empty-scope noise, as dogfooding surfaced. Kept platform-free and shared by the
 * frontend file collection and the warm-cache fingerprint so both agree.
 */
object SourceFilter {
    val SOURCE_EXTS = setOf("kt", "kts", "java")

    /** True if [file] is a source file ktrics should analyze (excludes Gradle build scripts). */
    fun isAnalyzable(file: File): Boolean {
        if (!file.isFile) return false
        if (file.extension.lowercase() !in SOURCE_EXTS) return false
        return !isBuildScript(file.name)
    }

    /** `build.gradle.kts`, `settings.gradle.kts`, any `*.gradle.kts`. */
    fun isBuildScript(fileName: String): Boolean = fileName.endsWith(".gradle.kts")
}
