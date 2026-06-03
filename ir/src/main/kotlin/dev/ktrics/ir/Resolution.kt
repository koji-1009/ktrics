package dev.ktrics.ir

import kotlinx.serialization.Serializable

/**
 * Confidence of a coupling/cohesion edge.
 *
 * Resolution default is `auto`: resolve when a classpath/source-root is available, degrade silently
 * to name-based otherwise. Every coupling/cohesion result is stamped so the agent reads the
 * confidence — surfaced as `resolution: resolved|name-based` in the `ai` report.
 */
@Serializable
enum class Resolution {
    /** Backed by a resolved symbol from the shared symbol space (cross-language edges included). */
    RESOLVED,

    /** The classpath could not resolve this edge; matched by name only. May over- or under-count. */
    NAME_BASED,
    ;

    val wireName: String get() =
        when (this) {
            RESOLVED -> "resolved"
            NAME_BASED -> "name-based"
        }

    companion object {
        /** Worst-of: a scope is only fully resolved if every contributing edge resolved. */
        fun weakest(edges: Iterable<Resolution>): Resolution = if (edges.any { it == NAME_BASED }) NAME_BASED else RESOLVED
    }
}
