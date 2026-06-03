package dev.ktrics.engine

import dev.ktrics.ir.Resolution
import dev.ktrics.testsupport.sourceUnit
import dev.ktrics.testsupport.typeDecl
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The module-aware project index: package coupling (Martin Ce/Ca) and supertype resolution. */
class ProjectIndexImplTest {
    // com.a imports a type from com.b and a JDK type; com.b imports nothing.
    private val index =
        ProjectIndexImpl(
            listOf(
                sourceUnit(
                    path = "a/A.kt",
                    pkg = "com.a",
                    imports = listOf("com.b.B", "java.util.List"),
                    types = listOf(typeDecl("A", pkg = "com.a")),
                ),
                sourceUnit(path = "b/B.kt", pkg = "com.b", imports = emptyList(), types = listOf(typeDecl("B", pkg = "com.b"))),
            ),
        )

    @Test
    fun `internal packages are those declared by source units`() {
        index.internalPackages shouldContainExactlyInAnyOrder listOf("com.a", "com.b")
    }

    @Test
    fun `efferent coupling lists the OTHER packages a package imports (internal and external)`() {
        // `com.b.B` → package com.b; `java.util.List` → package java.util; com.a itself is excluded.
        index.efferentPackagesOf("com.a") shouldContainExactlyInAnyOrder listOf("com.b", "java.util")
        index.efferentPackagesOf("com.b") shouldBe emptySet()
    }

    @Test
    fun `afferent coupling lists in-project packages depending on this one`() {
        // com.a depends on com.b → com.b's afferent set is {com.a}. The external java.util has none.
        index.afferentPackagesOf("com.b") shouldContainExactlyInAnyOrder listOf("com.a")
        index.afferentPackagesOf("com.a") shouldBe emptySet()
    }

    @Test
    fun `afferent coupling unions every in-project package depending on the same one`() {
        // com.a AND com.c both import com.b → com.b's afferent set is the UNION {com.a, com.c}.
        // Guards the accumulation combiner: a second dependent must add to (not overwrite) the first.
        val idx =
            ProjectIndexImpl(
                listOf(
                    sourceUnit(path = "a/A.kt", pkg = "com.a", imports = listOf("com.b.B"), types = listOf(typeDecl("A", pkg = "com.a"))),
                    sourceUnit(path = "c/C.kt", pkg = "com.c", imports = listOf("com.b.B"), types = listOf(typeDecl("C", pkg = "com.c"))),
                    sourceUnit(path = "b/B.kt", pkg = "com.b", imports = emptyList(), types = listOf(typeDecl("B", pkg = "com.b"))),
                ),
            )
        idx.afferentPackagesOf("com.b") shouldContainExactlyInAnyOrder listOf("com.a", "com.c")
    }

    @Test
    fun `types are indexed by qualified name and by package`() {
        index.typeByQName("com.a.A")!!.name shouldBe "A"
        index.typeByQName("com.absent.X") shouldBe null
        index.typesInPackage("com.a").map { it.name } shouldBe listOf("A")
        index.packageNames() shouldContainExactlyInAnyOrder listOf("com.a", "com.b")
    }

    @Test
    fun `a supertype resolves by import, then by a unique simple name, else stays unresolved`() {
        val nameBased = { s: String -> dev.ktrics.ir.TypeRef(s, null, null, Resolution.NAME_BASED) }
        val idx =
            ProjectIndexImpl(
                listOf(
                    // a.Base is the resolution target; b.Lonely is a unique simple name across the project.
                    sourceUnit(path = "a/Base.kt", pkg = "a", types = listOf(typeDecl("Base", pkg = "a"))),
                    sourceUnit(path = "b/Lonely.kt", pkg = "b", types = listOf(typeDecl("Lonely", pkg = "b"))),
                    // c.ByImport extends Base, resolved via its explicit import (not same-package).
                    sourceUnit(
                        path = "c/ByImport.kt",
                        pkg = "c",
                        imports = listOf("a.Base"),
                        types = listOf(typeDecl("ByImport", pkg = "c", supertypes = listOf(nameBased("Base")))),
                    ),
                    // d.ByUnique extends Lonely with no import — resolved as the unique in-project simple name.
                    sourceUnit(
                        path = "d/ByUnique.kt",
                        pkg = "d",
                        types = listOf(typeDecl("ByUnique", pkg = "d", supertypes = listOf(nameBased("Lonely")))),
                    ),
                    // e.Dangling extends a type that exists nowhere → the edge stays unresolved.
                    sourceUnit(
                        path = "e/Dangling.kt",
                        pkg = "e",
                        types = listOf(typeDecl("Dangling", pkg = "e", supertypes = listOf(nameBased("Ghost")))),
                    ),
                ),
            )
        idx.directSupertypeQNames("c.ByImport") shouldBe listOf("a.Base")
        idx.directSupertypeQNames("d.ByUnique") shouldBe listOf("b.Lonely")
        idx.directSupertypeQNames("e.Dangling") shouldBe emptyList()
    }

    @Test
    fun `a same-package supertype resolves by simple name`() {
        val idx =
            ProjectIndexImpl(
                listOf(
                    sourceUnit(
                        path = "p/Types.kt",
                        pkg = "p",
                        types =
                            listOf(
                                typeDecl("Base", pkg = "p"),
                                typeDecl("Child", pkg = "p").let {
                                    // Child extends Base (a same-package, name-based supertype reference).
                                    dev.ktrics.ir.TypeDecl(
                                        it.kind, "Child", "p.Child", false,
                                        listOf(dev.ktrics.ir.TypeRef("Base", null, null, Resolution.NAME_BASED)),
                                        emptyList(), emptyList(), emptyList(), it.modifiers, emptyList(), it.span, it.node, it.lang,
                                    )
                                },
                            ),
                    ),
                ),
            )
        idx.directSupertypeQNames("p.Child") shouldBe listOf("p.Base")
        idx.childrenCountOf("p.Base") shouldBe 1
        idx.inheritanceResolution("p.Child") shouldBe Resolution.NAME_BASED
    }
}
