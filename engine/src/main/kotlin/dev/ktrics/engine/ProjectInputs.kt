package dev.ktrics.engine

import dev.ktrics.config.Presets
import dev.ktrics.frontend.java.JavaFrontend
import dev.ktrics.frontend.kotlin.KotlinFrontend
import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
import dev.ktrics.langapi.NodeClassifier
import dev.ktrics.unused.UnusedConfig
import java.io.File

/**
 * Shared construction of the per-language classifiers and the unused-detector config from a resolved
 * project, so `analyze`, `unused`, and `inspect` build the call/reachability graph the same way.
 */
object ProjectInputs {
    /** A `(lang) -> classifier` selector, resolution-backed when the project asked for it. */
    fun classifierFor(
        projectRoot: File,
        resolved: Boolean,
    ): (Lang) -> NodeClassifier {
        val kotlin = KotlinFrontend(projectRoot, resolved).classifier
        val java = JavaFrontend(projectRoot, resolved).classifier
        return { lang -> if (lang == Lang.KOTLIN) kotlin else java }
    }

    /**
     * The unused-detector config (entry points + keep-alive presets) from the effective config.
     * Presets are the union of the declared list and the ones auto-detected from [units]' imports
     * (the code importing `androidx.*` IS an Android app); `unused: { auto-presets: false }` turns
     * the detection off.
     */
    fun unusedConfig(
        resolved: ResolvedProject,
        units: List<SourceUnit>,
        includeTests: Boolean = false,
    ): UnusedConfig {
        val declared = resolved.config.unused.presets
        val auto = if (resolved.config.unused.autoPresets) Presets.detect(units.flatMap { it.imports }) else emptySet()
        val presets = declared.toSet() + auto
        val base =
            UnusedConfig(
                entryPoints = resolved.config.unused.entryPoints.toSet() + "main",
                keepAliveAnnotations = Presets.keepAliveAnnotations(presets, resolved.config.unused.ignoreAnnotations),
                keepAliveSupertypes = Presets.keepAliveSupertypes(presets),
            )
        // `--include-tests` widens the report into test trees, which are excluded by default.
        return if (includeTests) base.copy(testGlobs = emptyList()) else base
    }
}
