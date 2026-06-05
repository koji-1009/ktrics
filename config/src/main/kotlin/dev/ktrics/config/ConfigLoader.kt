package dev.ktrics.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import java.io.File
import java.security.MessageDigest

/** Outcome of loading `ktrics.yaml`: the config plus the problems/warnings doctor will report. */
data class ConfigLoad(
    val config: KtricsConfig,
    val source: File?,
    val problems: List<String>,
    /** Stable hash of the file contents (empty when absent); part of the warm-cache key. */
    val hash: String,
    /** Non-fatal advisories (e.g. unknown top-level keys); doctor surfaces these as warnings. */
    val warnings: List<String> = emptyList(),
)

/**
 * Loads `ktrics.yaml`. Walks the YAML node tree rather than relying on strict
 * deserialization, so it tolerates the documented lenient shapes — chiefly a metric value that is an
 * object `{warning, error}` OR a bare `false`. Unknown keys are collected as problems, not failures,
 * for `ktrics doctor` to surface.
 */
object ConfigLoader {
    fun find(
        projectRoot: File,
        explicit: File? = null,
    ): File? {
        explicit?.let { return it.takeIf(File::isFile) }
        return File(projectRoot, "ktrics.yaml").takeIf { it.isFile }
            ?: File(projectRoot, "ktrics.yml").takeIf { it.isFile }
    }

    fun load(
        projectRoot: File,
        explicit: File? = null,
    ): ConfigLoad {
        val file =
            find(projectRoot, explicit)
                ?: return ConfigLoad(KtricsConfig.DEFAULT, null, emptyList(), "")
        val text = file.readText()
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()
        val config =
            runCatching { parse(text, problems, warnings) }.getOrElse {
                problems.add("failed to parse ${file.name}: ${it.message}")
                KtricsConfig.DEFAULT
            }
        return ConfigLoad(config, file, problems, hash(text), warnings)
    }

    fun parse(
        text: String,
        problems: MutableList<String> = ArrayList(),
        warnings: MutableList<String> = ArrayList(),
    ): KtricsConfig {
        val root = Yaml.default.parseToYamlNode(text) as? YamlMap ?: return KtricsConfig.DEFAULT
        val k = root.child("ktrics") as? YamlMap ?: root
        warnUnknownKeys(k, KNOWN_TOP_LEVEL_KEYS, warnings)
        return KtricsConfig(
            compose = k.strictBool("compose", "compose", problems) ?: false,
            test = k.strictBool("test", "test", problems) ?: false,
            resolution =
                k.string("resolution")?.let {
                    ResolutionMode.fromId(it) ?: warn(problems, "resolution", it)
                } ?: ResolutionMode.AUTO,
            modules = parseModules(k.child("modules") as? YamlMap, problems),
            metrics = parseMetrics(k.child("metrics") as? YamlMap, problems),
            unused = parseUnused(k.child("unused") as? YamlMap, problems),
            exclude = (k.child("exclude") as? YamlList).strings(),
            snapshot = SnapshotConfig(mode = (k.child("snapshot") as? YamlMap)?.string("mode") ?: "baseline"),
        )
    }

    private fun parseModules(
        node: YamlMap?,
        problems: MutableList<String>,
    ): ModulesConfig {
        if (node == null) return ModulesConfig()
        val discover =
            node.string("discover")?.let {
                DiscoverMode.fromId(it) ?: warn(problems, "modules.discover", it)
            } ?: DiscoverMode.OFF
        val declared =
            (node.child("declared") as? YamlList)?.items?.mapNotNull { item ->
                val m = item as? YamlMap ?: return@mapNotNull null
                val name = m.string("name") ?: return@mapNotNull null.also { problems.add("module without a name") }
                DeclaredModule(
                    name = name,
                    srcRoots = (m.child("srcRoots") as? YamlList).strings(),
                    classpath = (m.child("classpath") as? YamlList).strings(),
                    dependsOn = (m.child("dependsOn") as? YamlList).strings(),
                )
            }.orEmpty()
        return ModulesConfig(discover, declared)
    }

    private fun parseMetrics(
        node: YamlMap?,
        problems: MutableList<String>,
    ): Map<String, MetricEntry> {
        if (node == null) return emptyMap()
        return node.entries.entries.mapNotNull { (keyNode, valueNode) ->
            val id = keyNode.content
            when (valueNode) {
                is YamlScalar -> {
                    val b = parseYamlBool(valueNode.content)
                    if (b != null) {
                        id to MetricEntry(enabled = b)
                    } else {
                        problems.add("metric '$id' has an unexpected scalar value '${valueNode.content}'")
                        null
                    }
                }
                is YamlMap ->
                    id to
                        MetricEntry(
                            enabled = valueNode.strictBool("enabled", "metric '$id' enabled", problems),
                            warning = valueNode.threshold("warning", "metric '$id'", problems),
                            error = valueNode.threshold("error", "metric '$id'", problems),
                            java =
                                (valueNode.child("java") as? YamlMap)?.let {
                                    PerLangThresholds(
                                        it.threshold("warning", "metric '$id' (java)", problems),
                                        it.threshold("error", "metric '$id' (java)", problems),
                                    )
                                },
                            kotlin =
                                (valueNode.child("kotlin") as? YamlMap)?.let {
                                    PerLangThresholds(
                                        it.threshold("warning", "metric '$id' (kotlin)", problems),
                                        it.threshold("error", "metric '$id' (kotlin)", problems),
                                    )
                                },
                        )
                else -> null
            }
        }.toMap()
    }

    private fun parseUnused(
        node: YamlMap?,
        problems: MutableList<String>,
    ): UnusedConfig {
        if (node == null) return UnusedConfig()
        return UnusedConfig(
            entryPoints = (node.child("entry-points") as? YamlList).strings().ifEmpty { listOf("main", "@Test") },
            ignoreAnnotations = (node.child("ignore-annotations") as? YamlList).strings(),
            presets = (node.child("presets") as? YamlList).strings(),
            autoPresets = node.strictBool("auto-presets", "unused.auto-presets", problems) ?: true,
        )
    }

    private fun <T> warn(
        problems: MutableList<String>,
        key: String,
        value: String,
    ): T? {
        problems.add("invalid value '$value' for '$key'")
        return null
    }

    /** The top-level keys under `ktrics:` the loader understands; mirrors the JSON schema's properties. */
    private val KNOWN_TOP_LEVEL_KEYS =
        setOf("compose", "test", "resolution", "modules", "metrics", "unused", "exclude", "snapshot")

    /**
     * Flags keys present in [node] but absent from [known] as warnings. The JSON schema sets
     * `additionalProperties: false`, so an unknown key (typically a typo) is silently dropped today;
     * this surfaces it without failing the config, mirroring `additionalProperties` at the doctor level.
     */
    private fun warnUnknownKeys(
        node: YamlMap,
        known: Set<String>,
        warnings: MutableList<String>,
    ) {
        node.entries.entries.forEach { (keyNode, _) ->
            val key = keyNode.content
            if (key !in known) warnings.add("unknown config key '$key' (not in the ktrics schema; ignored)")
        }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /**
     * Parses a YAML boolean leniently per YAML 1.1/1.2 conventions: case-insensitive `true`/`yes`/`on`
     * map to `true`, `false`/`no`/`off` to `false`. Returns null for anything else so the caller can
     * raise an "unexpected scalar value" problem.
     */
    private fun parseYamlBool(raw: String): Boolean? =
        when (raw.trim().lowercase()) {
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> null
        }

    // --- YamlNode accessors ---
    private fun YamlMap.child(key: String): YamlNode? = entries.entries.firstOrNull { it.key.content == key }?.value

    private fun YamlMap.string(key: String): String? = (child(key) as? YamlScalar)?.content

    /**
     * A metric threshold MUST be a number: a non-numeric value (`warning: "abc"`, `warning: true`,
     * a nested map) was previously dropped to null silently, so a gate with no built-in default never
     * fired while the run still exited 0 — exactly the failure dartrics 0.8.0 fixed. A QUOTED number
     * (`"10"`) is indistinguishable from a bare one here (kaml resolves the scalar before we see it)
     * and is accepted as that number.
     */
    private fun YamlMap.threshold(
        key: String,
        label: String,
        problems: MutableList<String>,
    ): Double? {
        val node = child(key) ?: return null
        val content = (node as? YamlScalar)?.content
        val value = content?.toDoubleOrNull()
        if (value == null) {
            problems.add("$label threshold '$key' must be a number (got '${content ?: "non-scalar"}')")
        }
        return value
    }

    /**
     * A boolean flag parsed leniently (`true`/`yes`/`on`, case-insensitive — YAML 1.1 spellings a
     * hand-written config plausibly uses); anything else present-but-unparseable is recorded as a
     * problem instead of silently reading as "not set".
     */
    private fun YamlMap.strictBool(
        key: String,
        label: String,
        problems: MutableList<String>,
    ): Boolean? {
        val node = child(key) ?: return null
        val content = (node as? YamlScalar)?.content
        val value = content?.let { parseYamlBool(it) }
        if (value == null) {
            problems.add("'$label' must be a boolean (got '${content ?: "non-scalar"}')")
        }
        return value
    }

    private fun YamlList?.strings(): List<String> = this?.items?.mapNotNull { (it as? YamlScalar)?.content } ?: emptyList()
}
