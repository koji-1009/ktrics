package dev.ktrics.ir

/**
 * Visibility, normalized across both languages.
 *
 * Java `public`/`protected`/(package-private)/`private`; Kotlin `public`(default)/`internal`/
 * `protected`/`private`. `internal` is "narrower-public" — in scope for unused detection, unlike
 * `private` which the compiler/IDE already covers.
 */
enum class Visibility {
    PUBLIC,
    PROTECTED,
    INTERNAL,
    PACKAGE_PRIVATE,
    PRIVATE,
    ;

    /** Surface that public-API reachability considers. `private` is skipped. */
    val isApiSurface: Boolean get() = this == PUBLIC || this == PROTECTED || this == INTERNAL
}

/**
 * Declaration modifiers, normalized. `raw` keeps every source modifier token so a calculator can
 * reach for a language-specific one the normalized flags don't capture (e.g. `tailrec`, `synchronized`).
 */
data class Modifiers(
    val visibility: Visibility,
    val isAbstract: Boolean = false,
    val isFinal: Boolean = false,
    val isStatic: Boolean = false,
    val isOpen: Boolean = false,
    val isSealed: Boolean = false,
    val isData: Boolean = false,
    val isOverride: Boolean = false,
    val isCompanion: Boolean = false,
    val isInner: Boolean = false,
    val raw: Set<String> = emptySet(),
) {
    companion object {
        val PUBLIC = Modifiers(Visibility.PUBLIC)
    }
}

/** A formal parameter; `hasDefault` lets Kotlin discount defaulted params for number-of-parameters. */
data class Param(
    val name: String,
    val typeName: String,
    val hasDefault: Boolean = false,
    val isVararg: Boolean = false,
    /** True for `boolean`/`Boolean` — feeds boolean-trap. */
    val isBoolean: Boolean = false,
)
