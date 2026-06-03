package dev.ktrics.metric

import kotlinx.serialization.Serializable

/**
 * Per-language warn/error thresholds. A null bound means "no threshold on that side"
 * (e.g. an Informational metric, or method-length which only measures). Per-language overrides let
 * `number-of-parameters` warn at 4 on Java but 6 on Kotlin (named-arg call sites blunt the trap).
 */
@Serializable
data class Thresholds(
    val warning: Double? = null,
    val error: Double? = null,
) {
    fun severityFor(
        value: Double,
        polarity: Polarity,
    ): Severity? {
        val breaches = breachPredicate(polarity) ?: return null
        return when {
            error != null && breaches(value, error) -> Severity.ERROR
            warning != null && breaches(value, warning) -> Severity.WARNING
            else -> null
        }
    }

    /** How a value breaches a bound for this polarity (≥ for lower-is-better, ≤ for higher); null = never. */
    private fun breachPredicate(polarity: Polarity): ((Double, Double) -> Boolean)? =
        when (polarity) {
            Polarity.LOWER_IS_BETTER -> { value, bound -> value >= bound }
            Polarity.HIGHER_IS_BETTER -> { value, bound -> value <= bound }
            Polarity.INFORMATIONAL -> null
        }

    companion object {
        val NONE = Thresholds()

        fun warn(value: Double) = Thresholds(warning = value)

        fun warnError(
            warn: Double,
            err: Double,
        ) = Thresholds(warning = warn, error = err)
    }
}

/**
 * The catalogue entry for one metric: everything `rules`/`ai`/`md`/`sarif` need to explain it.
 * Auto-explain is on by default — every fired metric carries `rationale`, `refactorHints`,
 * `references`, `polarity` inline, so an agent never needs a second call to learn why.
 */
@Serializable
data class MetricDef(
    /** Stable kebab-case id; the contract surface (e.g. `cyclomatic-complexity`). */
    val id: String,
    val scopeKind: ScopeKind,
    val polarity: Polarity,
    val appliesTo: AppliesTo,
    /** Academic / industry source (e.g. "McCabe 1976"). */
    val source: String,
    /** One/two sentences: what it measures and why it matters. */
    val rationale: String,
    /** Concrete, ordered refactoring moves an agent can take. */
    val refactorHints: List<String>,
    /** Citations / further reading. */
    val references: List<String>,
    /** Default thresholds (both-language). Per-language overrides live in [perLanguage]. */
    val defaults: Thresholds,
    val perLanguage: Map<String, Thresholds> = emptyMap(),
    /** Off-by-default metrics (Halstead, MI) must be opted into via config. */
    val enabledByDefault: Boolean = true,
    /**
     * When [appliesTo] excludes a language, the structural reason it does not fire there.
     * Surfaced by auto-explain so absence is never read as a bug.
     */
    val structuralReason: String? = null,
) {
    /** Effective thresholds for a language: per-language override merged over defaults. */
    fun thresholdsFor(lang: dev.ktrics.ir.Lang): Thresholds {
        val override = perLanguage[lang.id] ?: return defaults
        return Thresholds(
            warning = override.warning ?: defaults.warning,
            error = override.error ?: defaults.error,
        )
    }
}
