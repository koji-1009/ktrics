package dev.ktrics.ir

/**
 * Resolved (or name-based) references attached to IR scopes as enrichments.
 *
 * `referencedTypes`/`calledSymbols`/`supertypes` from the `NodeClassifier` return these. Under the
 * one-host design they carry resolved symbols from the shared symbol space, so coupling/cohesion
 * metrics get cross-language Kotlin↔Java edges for free. An edge to an external
 * dependency missing from the classpath is the only case that degrades to [Resolution.NAME_BASED].
 */

/** A reference to a type (CBO, DIT/NOC, abstractness). */
data class TypeRef(
    /** Simple name as written at the reference site. */
    val name: String,
    /** Fully-qualified name when resolved; null when name-based. */
    val qualifiedName: String?,
    /** Package of the resolved type; drives Martin package coupling. */
    val packageName: String?,
    val resolution: Resolution,
    /** True when the resolved type lives outside the analysed module graph (an external dependency). */
    val external: Boolean = false,
) {
    /** The best stable key available: qualified name when resolved, else the bare name. */
    val key: String get() = qualifiedName ?: name
}

/** A reference to a callable (RFC: methods ∪ called methods). */
data class SymbolRef(
    val name: String,
    /** Declaring type's qualified name when resolved. */
    val container: String?,
    val resolution: Resolution,
) {
    val key: String get() = container?.let { "$it.$name" } ?: name
}

/** Halstead operator/operand classification of a single lexical token. */
data class Token(
    val text: String,
    val kind: TokenKind,
)

enum class TokenKind { OPERATOR, OPERAND, OTHER }
