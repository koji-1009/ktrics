package dev.ktrics.report

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Emits the canonical [AnalysisReport] as pretty JSON — the format `ktrics report <input.json>`
 * re-reads. Field names are stable through 0.x.
 */
class JsonReporter : Reporter {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            prettyPrintIndent = "  "
        }

    override fun render(report: AnalysisReport): String = json.encodeToString(report)

    companion object {
        private val parser =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        /** Re-reads a saved JSON report (for `ktrics report`). */
        fun parse(text: String): AnalysisReport = parser.decodeFromString(text)
    }
}
