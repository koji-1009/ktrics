package dev.ktrics.ir

import dev.ktrics.testsupport.fieldDecl
import dev.ktrics.testsupport.fnDecl
import dev.ktrics.testsupport.sourceUnit
import dev.ktrics.testsupport.typeDecl
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The normalized IR model: nested-type walks, function aggregation, and idiom flags. */
class ModelTest {
    @Test
    fun `selfAndNested walks a type and its nested types depth-first`() {
        val inner = typeDecl("Inner")
        val middle = typeDecl("Middle", nested = listOf(inner))
        val outer = typeDecl("Outer", nested = listOf(middle))
        outer.selfAndNested().map { it.name }.toList() shouldContainExactly listOf("Outer", "Middle", "Inner")
    }

    @Test
    fun `allTypes flattens every top-level and nested type in the unit`() {
        val nested = typeDecl("Nested")
        val top = typeDecl("Top", nested = listOf(nested))
        val unit = sourceUnit(types = listOf(top))
        unit.allTypes().map { it.name }.toList() shouldContainExactly listOf("Top", "Nested")
    }

    @Test
    fun `allFunctions unions top-level functions with every type's methods`() {
        val method = fnDecl("method")
        val type = typeDecl("T", methods = listOf(method))
        val unit = sourceUnit(types = listOf(type), topLevelFns = listOf(fnDecl("loose")))
        unit.allFunctions().map { it.name }.toSet() shouldBe setOf("loose", "method")
    }

    @Test
    fun `isDataLike is true for a data class and a record but not a plain class`() {
        typeDecl("Point", isData = true).isDataLike shouldBe true
        typeDecl("Rec", kind = TypeKind.RECORD).isDataLike shouldBe true
        typeDecl("Service").isDataLike shouldBe false
    }

    @Test
    fun `a function declaration carries its parsed parts`() {
        val fn = fnDecl("build", isConstructor = true)
        fn.name shouldBe "build"
        fn.isConstructor shouldBe true
        fn.lang shouldBe Lang.KOTLIN
        // bodyNode defaults to the declaration node in the builder; both are present.
        (fn.bodyNode != null) shouldBe true
    }

    @Test
    fun `a field declaration carries its type and property flag`() {
        val f = fieldDecl("count", typeName = "Int", isProperty = true)
        f.name shouldBe "count"
        f.typeName shouldBe "Int"
        f.isProperty shouldBe true
    }

    @Test
    fun `a source unit exposes its package, path and members`() {
        val unit = sourceUnit(path = "src/Foo.kt", pkg = "com.x", topLevelProps = listOf(fieldDecl("p")))
        unit.path shouldBe "src/Foo.kt"
        unit.packageName shouldBe "com.x"
        unit.lang shouldBe Lang.KOTLIN
        unit.topLevelProps.single().name shouldBe "p"
    }

    @Test
    fun `an empty unit yields no types or functions`() {
        val unit = sourceUnit()
        unit.allTypes().toList() shouldBe emptyList()
        unit.allFunctions().toList() shouldBe emptyList()
    }
}
