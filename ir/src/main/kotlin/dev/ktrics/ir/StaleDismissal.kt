package dev.ktrics.ir

import kotlinx.serialization.Serializable

/**
 * A dismissal directive that matched NO violation in this run — the violation it suppressed no
 * longer fires (fixed, renamed, or below threshold), so the directive is a stale entry to remove
 * (sibling of dartrics' `staleDismissals`). Reference information for the loop, never a gate.
 */
@Serializable
data class StaleDismissal(
    /** `sidecar` or `comment`. */
    val source: String,
    /** File the directive targets (comment: the file carrying it), when known. */
    val file: String? = null,
    /** 1-based line of a comment directive; null for sidecar entries. */
    val line: Int? = null,
    /** Metric id the directive names; null for a dismiss-all directive. */
    val metric: String? = null,
    /** Sidecar selector fields, when present. */
    val scope: String? = null,
    val id: String? = null,
    val reason: String,
) {
    /** Human-readable `file:line` location, falling back to the channel for line-less sidecar entries. */
    fun where(): String = listOfNotNull(file, line?.toString()).joinToString(":").ifEmpty { "sidecar" }
}
