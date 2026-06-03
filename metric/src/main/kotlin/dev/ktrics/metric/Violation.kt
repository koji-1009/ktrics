package dev.ktrics.metric

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.Span
import kotlinx.serialization.Serializable

/**
 * A raw metric measurement for one scope, before threshold comparison. Every enabled lens that
 * applies produces one of these per scope; the engine turns the ones that breach a threshold into
 * [Violation]s. Kept so `regression` can diff measurements that did not fire too.
 */
@Serializable
data class MetricResult(
    val metricId: String,
    /** Project-relative file path. */
    val file: String,
    /** Dotted scope path, e.g. `com.example.Foo.bar`. */
    val scope: String,
    /** Human label for the scope, e.g. `Foo.bar(int)`. */
    val scopeName: String,
    val scopeKind: ScopeKind,
    val lang: Lang,
    val value: Double,
    /** Confidence for coupling/cohesion metrics; null for purely syntactic ones. */
    val resolution: Resolution? = null,
    val span: Span,
)

/**
 * A fired metric: a [MetricResult] that breached its threshold, enriched inline with everything an
 * agent needs to act (auto-explain). The `ai`/`json`/`md`/`sarif` reporters render
 * these; the stable [id] lets a loop track "fix didn't take".
 */
@Serializable
data class Violation(
    /** Stable id: first 16 hex of sha256("file|scope|metric"). */
    val id: String,
    val metricId: String,
    val severity: Severity,
    val polarity: Polarity,
    val appliesTo: AppliesTo,
    val file: String,
    val scope: String,
    val scopeName: String,
    val scopeKind: ScopeKind,
    val lang: Lang,
    val value: Double,
    /** The threshold that was breached (warning or error bound). */
    val threshold: Double,
    val resolution: Resolution? = null,
    val span: Span,
    // --- auto-explain (inline; no second call needed) ---
    val rationale: String,
    val refactorHints: List<String>,
    val references: List<String>,
    // --- dismissal state ---
    val dismissal: DismissalState = DismissalState.None,
    /**
     * For complexity lenses: branch coverage ≥ 0.8 from JaCoCo. A complex method that is
     * thoroughly tested is surfaced as justified, so the loop can choose to accept it.
     */
    val complexityJustified: Boolean = false,
) {
    companion object {
        /** Builds a [Violation] from a measurement, the breached severity/threshold, and the def. */
        fun from(
            result: MetricResult,
            def: MetricDef,
            severity: Severity,
            threshold: Double,
        ): Violation =
            Violation(
                id = StableId.of(result.file, result.scope, result.metricId),
                metricId = result.metricId,
                severity = severity,
                polarity = def.polarity,
                appliesTo = def.appliesTo,
                file = result.file,
                scope = result.scope,
                scopeName = result.scopeName,
                scopeKind = result.scopeKind,
                lang = result.lang,
                value = result.value,
                threshold = threshold,
                resolution = result.resolution,
                span = result.span,
                rationale = def.rationale,
                refactorHints = def.refactorHints,
                references = def.references,
            )
    }
}

/** Dismissal outcome for a violation. */
@Serializable
sealed interface DismissalState {
    /** Not dismissed; a live violation. */
    @Serializable
    data object None : DismissalState

    /** Dismissed with an accepted reason; suppressed from the failing set. */
    @Serializable
    data class Dismissed(val reason: String, val source: String) : DismissalState

    /** A dismissal whose reason was too short (< minReasonLength) — stays LIVE with this flag. */
    @Serializable
    data class Rejected(val reason: String, val minReasonLength: Int) : DismissalState
}
