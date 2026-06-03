package dev.ktrics.vcs

import dev.ktrics.metric.MetricResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A persisted set of measurements to diff later (`--snapshot`). */
@Serializable
data class Snapshot(val version: String, val measurements: List<MetricResult>)

/**
 * Saves/loads measurement snapshots so `--snapshot baseline` can diff the current run against a stored
 * baseline with no second analysis. The daemon keeps these warm. Default location is
 * `.ktrics/snapshot.json` under the project root; an explicit path overrides.
 */
class SnapshotStore(private val projectRoot: File) {
    private val json =
        Json {
            prettyPrint = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    fun resolve(mode: String): File =
        when (mode) {
            "baseline", "cache" -> File(projectRoot, ".ktrics/snapshot.json")
            else -> File(mode) // treated as an explicit path
        }

    /**
     * Loads a snapshot, or null when absent / corrupt / version-incompatible. When [expectedVersion] is
     * given, a baseline whose MAJOR version differs is rejected: field names are stable through 0.x, so a
     * cross-major baseline may carry a different [MetricResult] shape (and `ignoreUnknownKeys` would
     * silently drop renamed fields), making a diff against it misleading. A null result simply means
     * "no usable baseline", so the run re-saves a fresh one instead of diffing stale data.
     */
    fun load(
        file: File,
        expectedVersion: String? = null,
    ): Snapshot? =
        file.takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString<Snapshot>(it.readText()) }.getOrNull() }
            ?.takeIf { expectedVersion == null || majorOf(it.version) == majorOf(expectedVersion) }

    private fun majorOf(version: String): String = version.substringBefore('.')

    fun save(
        file: File,
        version: String,
        measurements: List<MetricResult>,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Snapshot(version, measurements)))
    }
}
