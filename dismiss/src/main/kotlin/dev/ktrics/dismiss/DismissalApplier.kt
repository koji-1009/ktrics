package dev.ktrics.dismiss

import dev.ktrics.metric.DismissalState
import dev.ktrics.metric.Violation
import java.io.File

/**
 * Applies the two dismissal channels to a set of violations. The YAML sidecar wins on collision
 * with a comment dismissal. A reason shorter than [Sidecar.minReasonLength] keeps the violation
 * LIVE with [DismissalState.Rejected]. `--strict-dismiss` ignores all dismissals.
 *
 * Returns ALL violations with their dismissal state set; the caller drops the [DismissalState.Dismissed]
 * ones from the failing set but keeps Rejected ones live (they still count, flagged).
 */
class DismissalApplier(
    private val projectRoot: File,
    private val sidecar: Sidecar,
    private val strict: Boolean,
) {
    private val commentCache = HashMap<String, CommentDismissals?>()

    fun apply(violations: List<Violation>): List<Violation> {
        if (strict) return violations.map { it.copy(dismissal = DismissalState.None) }
        return violations.map { v ->
            val dismissal = sidecarMatch(v) ?: commentMatch(v)
            if (dismissal == null) {
                v.copy(dismissal = DismissalState.None)
            } else if (dismissal.reason.length < sidecar.minReasonLength) {
                v.copy(dismissal = DismissalState.Rejected(dismissal.reason, sidecar.minReasonLength))
            } else {
                v.copy(dismissal = DismissalState.Dismissed(dismissal.reason, dismissal.source))
            }
        }
    }

    /** Partition: (live = not dismissed; includes Rejected) and (dismissed = suppressed). */
    fun partition(violations: List<Violation>): Pair<List<Violation>, List<Violation>> {
        val applied = apply(violations)
        val dismissed = applied.filter { it.dismissal is DismissalState.Dismissed }
        val live = applied.filter { it.dismissal !is DismissalState.Dismissed }
        return live to dismissed
    }

    private fun sidecarMatch(v: Violation): Dismissal? =
        sidecar.dismissals.firstOrNull { d ->
            when {
                d.id != null -> d.id == v.id
                d.metric != null && d.scope != null -> d.metric == v.metricId && d.scope == v.scope
                d.metric != null && d.file != null -> d.metric == v.metricId && d.file == v.file
                else -> false
            }
        }

    private fun commentMatch(v: Violation): Dismissal? = comments(v.file)?.forDeclaration(v.span.startLine, v.metricId)

    private fun comments(relativePath: String): CommentDismissals? =
        commentCache.getOrPut(relativePath) {
            File(projectRoot, relativePath).takeIf { it.isFile }?.let { CommentDismissals(it.readText()) }
        }
}
