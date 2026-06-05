package dev.ktrics.config

import dev.ktrics.module.ModuleGraph
import dev.ktrics.module.ModuleNode

/**
 * The parsed `ktrics.yaml`. A plain domain model (not directly `@Serializable`) so the
 * loader can accept the YAML's lenient shapes — notably a metric value that is either an object
 * `{warning, error}` or a bare `false` to disable it.
 */
data class KtricsConfig(
    /** @Composable skip rules (≈ dartrics flutter:true). */
    val compose: Boolean = false,
    /** JUnit/Kotlin-test size-metric skips. */
    val test: Boolean = false,
    val resolution: ResolutionMode = ResolutionMode.AUTO,
    val modules: ModulesConfig = ModulesConfig(),
    val metrics: Map<String, MetricEntry> = emptyMap(),
    val unused: UnusedConfig = UnusedConfig(),
    val exclude: List<String> = emptyList(),
    val snapshot: SnapshotConfig = SnapshotConfig(),
) {
    companion object {
        val DEFAULT = KtricsConfig()
    }
}

enum class ResolutionMode(val id: String) {
    AUTO("auto"),
    NAME_BASED("name-based"),
    RESOLVED("resolved"),
    ;

    companion object {
        fun fromId(id: String): ResolutionMode? = entries.firstOrNull { it.id == id.lowercase() }
    }
}

enum class DiscoverMode(val id: String) {
    OFF("off"),
    GRADLE("gradle"),
    MAVEN("maven"),
    ;

    companion object {
        fun fromId(id: String): DiscoverMode? = entries.firstOrNull { it.id == id.lowercase() }
    }
}

data class ModulesConfig(
    /** v2 auto-derivation switch (off | gradle | maven); off in v1. */
    val discover: DiscoverMode = DiscoverMode.OFF,
    val declared: List<DeclaredModule> = emptyList(),
) {
    /** Builds the [ModuleGraph] from the declared modules, or null when none are declared. */
    fun toGraph(): ModuleGraph? {
        if (declared.isEmpty()) return null
        return ModuleGraph(declared.map { ModuleNode(it.name, it.srcRoots, it.classpath, it.dependsOn) })
    }
}

data class DeclaredModule(
    val name: String,
    val srcRoots: List<String>,
    val classpath: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
)

/**
 * Per-metric config. `enabled = false` disables the metric (the YAML `metric: false` shorthand maps
 * here); `warning`/`error` override the both-language defaults; `java`/`kotlin` override per language.
 */
data class MetricEntry(
    val enabled: Boolean? = null,
    val warning: Double? = null,
    val error: Double? = null,
    val java: PerLangThresholds? = null,
    val kotlin: PerLangThresholds? = null,
)

data class PerLangThresholds(val warning: Double? = null, val error: Double? = null)

data class UnusedConfig(
    val entryPoints: List<String> = listOf("main", "@Test"),
    val ignoreAnnotations: List<String> = emptyList(),
    val presets: List<String> = emptyList(),
    /** Auto-enable presets from the analyzed sources' imports (`androidx.*` → android, …); on by default. */
    val autoPresets: Boolean = true,
)

data class SnapshotConfig(val mode: String = "baseline")
