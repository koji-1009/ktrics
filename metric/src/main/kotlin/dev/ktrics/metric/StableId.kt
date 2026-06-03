package dev.ktrics.metric

import java.security.MessageDigest

/**
 * Stable violation id: first 16 hex of `sha256("<file>|<scope>|<metric>")`.
 *
 * Stable across runs so an AI loop detects "fix didn't take" — the same violation keeps the same id
 * even as line numbers shift. Surfaces in json/ai/md and as `partialFingerprints.ktrics/v1` in SARIF.
 *
 * `<file>` is the project-relative path; `<scope>` is the dotted scope path (e.g. `com.x.Foo.bar`);
 * `<metric>` is the metric id. Deliberately NOT including the value or line, so the id survives edits.
 */
object StableId {
    const val SARIF_FINGERPRINT_KEY: String = "ktrics/v1"

    fun of(
        file: String,
        scope: String,
        metric: String,
    ): String {
        val input = "$file|$scope|$metric"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
