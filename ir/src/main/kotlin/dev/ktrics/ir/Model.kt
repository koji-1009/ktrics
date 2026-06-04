package dev.ktrics.ir

import com.intellij.psi.PsiElement

/**
 * The normalized, language-agnostic IR.
 *
 * Each scope carries structured metadata PLUS a [node] handle to its frontend [PsiElement], consumed
 * via the `NodeClassifier` for token-level and walk-based metrics. Resolved type/symbol references
 * from the shared symbol space attach as enrichments ([TypeRef]/[SymbolRef]); an edge to an external
 * dependency missing from the classpath is the only case that falls back to a name.
 *
 * Calculators NEVER special-case a language — they read this model and walk [node] through the
 * classifier. These types are intentionally not `@Serializable`: the IR
 * never crosses the socket, only the small normalized report does.
 */

/** Shape of a declared type. `sealed` is both a kind here and a [Modifiers] flag — DIT counts it. */
enum class TypeKind {
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    OBJECT,
    SEALED,
    ANNOTATION,
    ;

    /** Interfaces and annotations count as abstract for Martin abstractness A. */
    val isInherentlyAbstract: Boolean get() = this == INTERFACE || this == ANNOTATION
}

/** A parsed source file. Kotlin may hold many top-level types plus loose functions/properties. */
class SourceUnit(
    val path: String,
    val lang: Lang,
    val packageName: String,
    val imports: List<String>,
    val types: List<TypeDecl>,
    /** Kotlin top-level functions — belong to no class; escape WMC/NOM/LCOM/RFC. */
    val topLevelFns: List<FunctionDecl>,
    /** Kotlin top-level properties — same structural gap as top-level functions. */
    val topLevelProps: List<FieldDecl>,
    val span: Span,
    val node: PsiElement,
) {
    /** Depth-first walk of every type including nested ones. */
    fun allTypes(): Sequence<TypeDecl> = types.asSequence().flatMap { it.selfAndNested() }

    /**
     * Every function anywhere in the unit: top-level + each type's methods (recursively).
     * Consumed by :test-session's SessionFixture, which sits OUTSIDE the dogfood module graph
     * (ktrics.yaml omits the fixture modules), so the self-run `unused` lens reports this —
     * a deliberate keep, not dead code.
     */
    fun allFunctions(): Sequence<FunctionDecl> = topLevelFns.asSequence() + allTypes().flatMap { it.methods.asSequence() }
}

/** A class/interface/enum/record/object/sealed/annotation declaration. */
class TypeDecl(
    val kind: TypeKind,
    val name: String,
    /** Fully-qualified name; `package.Outer.Inner` for nested. Null only for anonymous types. */
    val qualifiedName: String?,
    val isAbstract: Boolean,
    val supertypes: List<TypeRef>,
    val fields: List<FieldDecl>,
    val methods: List<FunctionDecl>,
    val nested: List<TypeDecl>,
    val modifiers: Modifiers,
    val annotations: List<String>,
    val span: Span,
    val node: PsiElement,
    val lang: Lang,
) {
    /** True for Kotlin `data class` / Java `record` — idiom-aware skips apply. */
    val isDataLike: Boolean get() = modifiers.isData || kind == TypeKind.RECORD

    fun selfAndNested(): Sequence<TypeDecl> = sequenceOf(this) + nested.asSequence().flatMap { it.selfAndNested() }
}

/** A function/method/constructor. */
class FunctionDecl(
    val name: String,
    val params: List<Param>,
    val modifiers: Modifiers,
    val annotations: List<String>,
    val span: Span,
    /** The whole declaration node (signature + body). */
    val node: PsiElement,
    /** The body block, when present; null for abstract/interface methods. Body-length denominator. */
    val bodyNode: PsiElement?,
    val lang: Lang,
    val isConstructor: Boolean = false,
)

/** A field (Java) or property (Kotlin). LCOM4 connects methods that share these. */
class FieldDecl(
    val name: String,
    val typeName: String,
    val modifiers: Modifiers,
    val annotations: List<String>,
    val span: Span,
    val node: PsiElement,
    val isProperty: Boolean,
)
