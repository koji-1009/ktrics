package dev.ktrics.metric

import kotlinx.serialization.Serializable

/**
 * Direction of "better" for a metric. Drives how regression classifies a delta
 * (improved/regressed) and how the `ai` reporter words its hints. Mirrors the sibling tools' vocab.
 */
@Serializable
enum class Polarity {
    /** Smaller is healthier (most complexity/coupling lenses). */
    LOWER_IS_BETTER,

    /** Larger is healthier (e.g. cohesion-style measures, were any HigherIsBetter). */
    HIGHER_IS_BETTER,

    /** No inherent good/bad direction; surfaced for context only (e.g. instability I). */
    INFORMATIONAL,
    ;

    val wireName: String get() =
        when (this) {
            LOWER_IS_BETTER -> "LowerIsBetter"
            HIGHER_IS_BETTER -> "HigherIsBetter"
            INFORMATIONAL -> "Informational"
        }
}

/**
 * Which languages a metric structurally applies to. Set by structural validity, NOT by
 * favoring a language: the CK/Martin suite was defined for class-based, single-inheritance,
 * one-public-type-per-file code — Java is exactly that shape; Kotlin adds units those metrics never
 * modeled. When a metric does not apply, `rules`/`ai` auto-explain says WHY (the structural reason),
 * so an agent never reads absence as an oversight.
 */
@Serializable
enum class AppliesTo {
    JAVA,
    KOTLIN,
    BOTH,
    ;

    fun matches(lang: dev.ktrics.ir.Lang): Boolean =
        when (this) {
            BOTH -> true
            JAVA -> lang == dev.ktrics.ir.Lang.JAVA
            KOTLIN -> lang == dev.ktrics.ir.Lang.KOTLIN
        }

    val wireName: String get() =
        when (this) {
            JAVA -> "java"
            KOTLIN -> "kotlin"
            BOTH -> "both"
        }
}

/** The structural scope a metric measures. */
@Serializable
enum class ScopeKind { FUNCTION, TYPE, FILE, PACKAGE }

/** Threshold-breach level of a fired metric. */
@Serializable
enum class Severity {
    WARNING,
    ERROR,
    ;

    val wireName: String get() = name.lowercase()
}
