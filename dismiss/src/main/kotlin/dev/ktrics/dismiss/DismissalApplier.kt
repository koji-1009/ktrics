package dev.ktrics.dismiss

import dev.ktrics.ir.StaleDismissal
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

    /**
     * Directives that matched NO violation in this run (sibling of dartrics' `staleEntries`): the
     * violation they suppressed is gone — fixed, renamed, or below threshold — so the directive should
     * be removed. [violations] must be the PRE-dismissal set (a consumed-and-dismissed violation is
     * not stale) and pre-`--since` (a filtered-out file would make its dismissals look stale).
     * Comment directives are scanned over [analyzedFiles] only. Empty under `--strict-dismiss`
     * (everything would read as stale).
     */
    fun staleDismissals(
        violations: List<Violation>,
        analyzedFiles: Collection<String>,
    ): List<StaleDismissal> {
        if (strict) return emptyList()
        val staleSidecar =
            sidecar.dismissals
                .filter { d -> violations.none { v -> matches(d, v) } }
                .map { StaleDismissal("sidecar", it.file, null, it.metric, it.scope, it.id, it.reason) }
        val staleComments =
            analyzedFiles.sorted().flatMap { file ->
                val scan = comments(file) ?: return@flatMap emptyList()
                val consumed =
                    violations.filter { it.file == file }
                        .mapNotNullTo(HashSet()) { v -> scan.directiveLineFor(v.span.startLine, v.metricId) }
                scan.allDirectives()
                    .filter { it.line !in consumed }
                    .map { StaleDismissal("comment", file, it.line, it.metric, null, null, it.reason) }
            }
        return staleSidecar + staleComments
    }

    private fun sidecarMatch(v: Violation): Dismissal? = sidecar.dismissals.firstOrNull { matches(it, v) }

    private fun matches(
        d: Dismissal,
        v: Violation,
    ): Boolean =
        when {
            d.id != null -> d.id == v.id
            d.metric != null && d.scope != null -> d.metric == v.metricId && d.scope == v.scope
            d.metric != null && d.file != null -> d.metric == v.metricId && d.file == v.file
            else -> false
        }

    private fun commentMatch(v: Violation): Dismissal? = comments(v.file)?.forDeclaration(v.span.startLine, v.metricId)

    private fun comments(relativePath: String): CommentDismissals? =
        commentCache.getOrPut(relativePath) {
            File(projectRoot, relativePath).takeIf { it.isFile }?.let { CommentDismissals(it.readText()) }
        }
}
