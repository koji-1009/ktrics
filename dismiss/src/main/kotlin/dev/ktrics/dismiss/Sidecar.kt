package dev.ktrics.dismiss

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import java.io.File

/**
 * The parsed `ktrics-dismissals.yaml` sidecar. Wins on collision with comment dismissals.
 *
 * [warnings] carries non-fatal advisories raised while parsing (e.g. a malformed `minReasonLength`).
 * It defaults to empty so existing callers and [EMPTY] comparisons are unaffected; surface it wherever
 * diagnostics are reported.
 */
data class Sidecar(
    val dismissals: List<Dismissal>,
    val minReasonLength: Int,
    val warnings: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = Sidecar(emptyList(), DEFAULT_MIN_REASON_LENGTH)

        fun load(projectRoot: File): Sidecar {
            val file =
                File(projectRoot, "ktrics-dismissals.yaml").takeIf { it.isFile }
                    ?: File(projectRoot, "ktrics-dismissals.yml").takeIf { it.isFile }
                    ?: return EMPTY
            // A malformed sidecar (bad YAML, duplicate keys, anchors — kaml throws on all three) is a
            // config problem, not an internal fault: degrade to EMPTY with the failure surfaced as a
            // warning (mirrors ConfigLoader), instead of crashing analyze with exit 70.
            return runCatching { parse(file.readText()) }.getOrElse {
                EMPTY.copy(warnings = listOf("failed to parse ${file.name}: ${it.message}"))
            }
        }

        fun parse(text: String): Sidecar {
            val root = Yaml.default.parseToYamlNode(text) as? YamlMap ?: return EMPTY
            val warnings = ArrayList<String>()
            // Format-evolution gate (sibling of dartrics' required `version: 1`): a sidecar written for
            // a future, incompatible format must not be half-applied — its entries are ignored loudly.
            val version = (root.entry("version") as? YamlScalar)?.content
            if (version != null && version != "1") {
                return EMPTY.copy(warnings = listOf("unsupported ktrics-dismissals version '$version' (expected 1); dismissals ignored"))
            }
            val minReason = parseMinReasonLength(root.entry("minReasonLength") as? YamlScalar, warnings)
            val list =
                (root.entry("dismissals") as? YamlList)?.items?.mapNotNull { item ->
                    val m = item as? YamlMap ?: return@mapNotNull null
                    val reason = (m.entry("reason") as? YamlScalar)?.content ?: return@mapNotNull null
                    Dismissal(
                        reason = reason,
                        source = "sidecar",
                        id = (m.entry("id") as? YamlScalar)?.content,
                        metric = (m.entry("metric") as? YamlScalar)?.content,
                        file = (m.entry("file") as? YamlScalar)?.content,
                        scope = (m.entry("scope") as? YamlScalar)?.content,
                    )
                }.orEmpty()
            return Sidecar(list, minReason, warnings)
        }

        /**
         * Parses `minReasonLength`, falling back to [DEFAULT_MIN_REASON_LENGTH]. A key that is present but
         * not a non-negative integer (`oops`, `8.5`, `-3`) is a misconfiguration that would silently weaken
         * dismissal gating, so it is coerced to the default AND recorded in [warnings].
         */
        private fun parseMinReasonLength(
            node: YamlScalar?,
            warnings: MutableList<String>,
        ): Int {
            val raw = node?.content ?: return DEFAULT_MIN_REASON_LENGTH
            val parsed = raw.toIntOrNull()
            if (parsed == null || parsed < 0) {
                warnings.add(
                    "invalid minReasonLength '$raw' (expected a non-negative integer); using default $DEFAULT_MIN_REASON_LENGTH",
                )
                return DEFAULT_MIN_REASON_LENGTH
            }
            return parsed
        }

        private fun YamlMap.entry(key: String) = entries.entries.firstOrNull { it.key.content == key }?.value
    }
}
