@file:Suppress("LongParameterList") // Test-fixture builders mirror the IR's wide constructors by design.

package dev.ktrics.testsupport

import dev.ktrics.ir.FieldDecl
import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.Lang
import dev.ktrics.ir.Modifiers
import dev.ktrics.ir.Param
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.Span
import dev.ktrics.ir.TypeDecl
import dev.ktrics.ir.TypeKind
import dev.ktrics.ir.TypeRef
import dev.ktrics.ir.Visibility

/**
 * IR builders for pure-logic tests. Each decl carries a [stubNode] for its PSI handle, so tests that
 * only inspect normalized structure (skips, dismissals, reporters) need no session. For tests that
 * drive the reference graph, use [FakeClassifier.fn]/[FakeClassifier.type], which additionally
 * register canned refs against the node.
 */

/** A throwaway one-line span at [file]:[startLine]. */
fun testSpan(
    file: String = "Test.kt",
    startLine: Int = 1,
    endLine: Int = startLine,
): Span = Span(file, startLine, 1, endLine, 1, 0, 0)

fun param(
    name: String,
    typeName: String = "Any",
    hasDefault: Boolean = false,
    isVararg: Boolean = false,
    isBoolean: Boolean = typeName == "Boolean" || typeName == "boolean",
): Param = Param(name, typeName, hasDefault, isVararg, isBoolean)

fun fieldDecl(
    name: String,
    typeName: String = "Any",
    visibility: Visibility = Visibility.PRIVATE,
    annotations: List<String> = emptyList(),
    isProperty: Boolean = true,
): FieldDecl = FieldDecl(name, typeName, Modifiers(visibility), annotations, testSpan(), stubNode(), isProperty)

fun fnDecl(
    name: String,
    params: List<Param> = emptyList(),
    visibility: Visibility = Visibility.PUBLIC,
    annotations: List<String> = emptyList(),
    isConstructor: Boolean = false,
    modifiers: Modifiers = Modifiers(visibility),
    lang: Lang = Lang.KOTLIN,
    span: Span = testSpan(),
): FunctionDecl {
    val n = stubNode()
    return FunctionDecl(name, params, modifiers, annotations, span, n, n, lang, isConstructor)
}

fun typeDecl(
    name: String,
    kind: TypeKind = TypeKind.CLASS,
    pkg: String = "pkg",
    fields: List<FieldDecl> = emptyList(),
    methods: List<FunctionDecl> = emptyList(),
    nested: List<TypeDecl> = emptyList(),
    visibility: Visibility = Visibility.PUBLIC,
    annotations: List<String> = emptyList(),
    isData: Boolean = false,
    lang: Lang = Lang.KOTLIN,
    span: Span = testSpan(),
    supertypes: List<TypeRef> = emptyList(),
): TypeDecl =
    TypeDecl(
        kind = kind,
        name = name,
        qualifiedName = "$pkg.$name",
        isAbstract = kind.isInherentlyAbstract,
        supertypes = supertypes,
        fields = fields,
        methods = methods,
        nested = nested,
        modifiers = Modifiers(visibility, isData = isData),
        annotations = annotations,
        span = span,
        node = stubNode(),
        lang = lang,
    )

fun sourceUnit(
    path: String = "Test.kt",
    pkg: String = "pkg",
    types: List<TypeDecl> = emptyList(),
    topLevelFns: List<FunctionDecl> = emptyList(),
    topLevelProps: List<FieldDecl> = emptyList(),
    imports: List<String> = emptyList(),
    lang: Lang = Lang.KOTLIN,
): SourceUnit = SourceUnit(path, lang, pkg, imports, types, topLevelFns, topLevelProps, testSpan(path), stubNode())

// --- Reference-graph builders: register canned refs on the node so a FakeClassifier resolves them. ---

fun FakeClassifier.fn(
    name: String,
    calls: List<String> = emptyList(),
    types: List<String> = emptyList(),
    fieldAccesses: Set<String> = emptySet(),
    visibility: Visibility = Visibility.PUBLIC,
    annotations: List<String> = emptyList(),
    resolution: Resolution = Resolution.NAME_BASED,
    isConstructor: Boolean = false,
    lang: Lang = Lang.KOTLIN,
): FunctionDecl {
    val n = register(calls = calls, types = types, fieldAccesses = fieldAccesses, resolution = resolution)
    return FunctionDecl(name, emptyList(), Modifiers(visibility), annotations, testSpan(), n, n, lang, isConstructor)
}

fun FakeClassifier.type(
    name: String,
    pkg: String = "pkg",
    methods: List<FunctionDecl> = emptyList(),
    nested: List<TypeDecl> = emptyList(),
    supertypes: List<String> = emptyList(),
    kind: TypeKind = TypeKind.CLASS,
    visibility: Visibility = Visibility.PUBLIC,
    lang: Lang = Lang.KOTLIN,
): TypeDecl {
    val n = register(supertypes = supertypes)
    return TypeDecl(
        kind, name, "$pkg.$name", kind.isInherentlyAbstract,
        supertypes.map { dev.ktrics.ir.TypeRef(it, it, null, Resolution.NAME_BASED) },
        emptyList(), methods, nested, Modifiers(visibility), emptyList(), testSpan(), n, lang,
    )
}
