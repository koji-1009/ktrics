package dev.ktrics.unused

import com.intellij.psi.PsiElement
import dev.ktrics.ir.FieldDecl
import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.Lang
import dev.ktrics.ir.Modifiers
import dev.ktrics.ir.Param
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.Span
import dev.ktrics.ir.SymbolRef
import dev.ktrics.ir.Token
import dev.ktrics.ir.TypeDecl
import dev.ktrics.ir.TypeKind
import dev.ktrics.ir.TypeRef
import dev.ktrics.ir.Visibility
import dev.ktrics.langapi.NodeClassifier
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/**
 * Pure-logic test scaffolding for the call graph + unused detector. Both only ever pass an IR node
 * back to the classifier — they never dereference it — so a unique stub PsiElement plus a fake
 * classifier that returns canned references (looked up by identity) is enough; no live K2/PSI session.
 */

internal val SPAN = Span("Test.kt", 1, 1, 1, 1, 0, 0)

/** A unique stub PsiElement; no method on it is ever invoked by the code under test. */
internal fun stubNode(): PsiElement =
    Proxy.newProxyInstance(NodeClassifier::class.java.classLoader, arrayOf(PsiElement::class.java)) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            else -> null
        }
    } as PsiElement

/** Returns canned call/type references per node; everything else is a harmless stub. */
internal class FakeClassifier : NodeClassifier {
    private val calls = IdentityHashMap<PsiElement, List<String>>()
    private val types = IdentityHashMap<PsiElement, List<String>>()

    // Call- and type-edge resolution are tracked SEPARATELY so a decl can carry a resolved type edge AND a
    // name-based call edge — the exact shape that resolutionOf's sweep coverage defends.
    private val callResolution = IdentityHashMap<PsiElement, Resolution>()
    private val typeResolution = IdentityHashMap<PsiElement, Resolution>()

    /** Registers a node's outgoing references and returns it (used by the decl builders below). */
    fun register(
        calls: List<String>,
        types: List<String>,
        callResolution: Resolution,
        typeResolution: Resolution = callResolution,
    ): PsiElement {
        val n = stubNode()
        this.calls[n] = calls
        this.types[n] = types
        this.callResolution[n] = callResolution
        this.typeResolution[n] = typeResolution
        return n
    }

    override fun outgoingRefNames(scope: PsiElement): List<String> = calls[scope].orEmpty() + types[scope].orEmpty()

    override fun calledSymbols(scope: PsiElement): List<SymbolRef> =
        calls[scope].orEmpty().map { SymbolRef(it, null, callResolution[scope] ?: Resolution.NAME_BASED) }

    override fun referencedTypes(scope: PsiElement): List<TypeRef> =
        types[scope].orEmpty().map { TypeRef(it, null, null, typeResolution[scope] ?: Resolution.NAME_BASED) }

    override fun supertypes(type: PsiElement): List<TypeRef> = emptyList()

    override fun fieldAccesses(scope: PsiElement): Set<String> = emptySet()

    override fun parameters(fn: PsiElement): List<Param> = emptyList()

    override fun annotations(decl: PsiElement): List<String> = emptyList()

    override fun modifiers(decl: PsiElement): Modifiers = Modifiers.PUBLIC

    override fun tokens(scope: PsiElement): List<Token> = emptyList()

    override fun children(n: PsiElement): List<PsiElement> = emptyList()

    override fun text(n: PsiElement): String = ""

    override fun isCommentOrWhitespace(n: PsiElement): Boolean = false

    override fun isDecisionPoint(n: PsiElement): Boolean = false

    override fun isNestingBoundary(n: PsiElement): Boolean = false

    override fun isLogicalSequence(n: PsiElement): Boolean = false

    override fun isLoopOrBranch(n: PsiElement): Boolean = false

    override fun npathMultiplier(n: PsiElement): Int = 2
}

internal fun FakeClassifier.fn(
    name: String,
    calls: List<String> = emptyList(),
    types: List<String> = emptyList(),
    visibility: Visibility = Visibility.PUBLIC,
    annotations: List<String> = emptyList(),
    resolution: Resolution = Resolution.NAME_BASED,
    callResolution: Resolution = resolution,
    typeResolution: Resolution = resolution,
    lang: Lang = Lang.KOTLIN,
): FunctionDecl {
    val n = register(calls, types, callResolution, typeResolution)
    return FunctionDecl(name, emptyList(), Modifiers(visibility), annotations, SPAN, n, n, lang)
}

internal fun FakeClassifier.prop(
    name: String,
    types: List<String> = emptyList(),
    visibility: Visibility = Visibility.PUBLIC,
    annotations: List<String> = emptyList(),
): FieldDecl {
    val n = register(emptyList(), types, Resolution.NAME_BASED)
    return FieldDecl(name, "kotlin.Unit", Modifiers(visibility), annotations, SPAN, n, isProperty = true)
}

internal fun FakeClassifier.type(
    name: String,
    pkg: String = "pkg",
    methods: List<FunctionDecl> = emptyList(),
    nested: List<TypeDecl> = emptyList(),
    kind: TypeKind = TypeKind.CLASS,
    visibility: Visibility = Visibility.PUBLIC,
    lang: Lang = Lang.KOTLIN,
): TypeDecl {
    val n = register(emptyList(), emptyList(), Resolution.NAME_BASED)
    return TypeDecl(
        kind, name, "$pkg.$name", false, emptyList(), emptyList(), methods, nested,
        Modifiers(
            visibility,
        ),
        emptyList(), SPAN, n, lang,
    )
}

internal fun unit(
    path: String = "Test.kt",
    pkg: String = "pkg",
    fns: List<FunctionDecl> = emptyList(),
    types: List<TypeDecl> = emptyList(),
    props: List<FieldDecl> = emptyList(),
    lang: Lang = Lang.KOTLIN,
): SourceUnit = SourceUnit(path, lang, pkg, emptyList(), types, fns, props, SPAN, stubNode())
