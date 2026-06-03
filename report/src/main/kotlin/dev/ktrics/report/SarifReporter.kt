package dev.ktrics.report

import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.metric.Severity
import dev.ktrics.metric.StableId
import dev.ktrics.metric.Violation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * SARIF 2.1.0 output. The stable violation id surfaces as
 * `partialFingerprints["ktrics/v1"]` so a SARIF consumer can track "fix didn't take" across runs
 * exactly as the json/ai/md reporters do.
 */
class SarifReporter : Reporter {
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    override fun render(report: AnalysisReport): String {
        val sarif =
            buildJsonObject {
                put("version", "2.1.0")
                put("\$schema", "https://json.schemastore.org/sarif-2.1.0.json")
                putJsonArray("runs") {
                    add(
                        buildJsonObject {
                            putJsonObject("tool") {
                                putJsonObject("driver") {
                                    put("name", report.tool)
                                    put("version", report.version)
                                    put("informationUri", "https://github.com/koji-1009/ktrics")
                                    put("rules", rules())
                                }
                            }
                            put("results", results(report.violations))
                        },
                    )
                }
            }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), sarif)
    }

    private fun rules(): JsonArray =
        buildJsonArray {
            BuiltinMetrics.all.forEach { def ->
                add(
                    buildJsonObject {
                        put("id", def.id)
                        put("name", def.id)
                        putJsonObject("shortDescription") { put("text", def.source) }
                        putJsonObject("fullDescription") { put("text", def.rationale) }
                        putJsonObject("properties") {
                            put("polarity", def.polarity.wireName)
                            put("appliesTo", def.appliesTo.wireName)
                            putJsonArray("references") { def.references.forEach { add(it) } }
                        }
                        putJsonObject("help") { put("text", def.refactorHints.joinToString("\n") { "- $it" }) }
                    },
                )
            }
        }

    private fun results(violations: List<Violation>): JsonArray =
        buildJsonArray {
            Actionability.sort(violations).forEach { v ->
                add(
                    buildJsonObject {
                        put("ruleId", v.metricId)
                        put("level", if (v.severity == Severity.ERROR) "error" else "warning")
                        putJsonObject("message") {
                            put("text", "${v.scopeName}: ${v.metricId} = ${fmt(v.value)} (limit ${fmt(v.threshold)}). ${v.rationale}")
                        }
                        putJsonArray("locations") {
                            add(
                                buildJsonObject {
                                    putJsonObject("physicalLocation") {
                                        putJsonObject("artifactLocation") { put("uri", v.file) }
                                        putJsonObject("region") {
                                            put("startLine", v.span.startLine)
                                            put("startColumn", v.span.startColumn)
                                            put("endLine", v.span.endLine)
                                        }
                                    }
                                },
                            )
                        }
                        putJsonObject("partialFingerprints") {
                            // The stable id under the locked SARIF fingerprint key.
                            put(StableId.SARIF_FINGERPRINT_KEY, v.id)
                        }
                        putJsonObject("properties") {
                            put("language", v.lang.id)
                            v.resolution?.let { put("resolution", it.wireName) }
                        }
                    },
                )
            }
        }

    private fun fmt(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", d)
}
