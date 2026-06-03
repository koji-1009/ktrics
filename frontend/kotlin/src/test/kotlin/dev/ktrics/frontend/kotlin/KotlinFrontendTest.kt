package dev.ktrics.frontend.kotlin

import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeKind
import dev.ktrics.testsession.SessionFixture
import dev.ktrics.testsession.repoRoot
import dev.ktrics.testsession.type
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Lowering a Kotlin file to the normalized IR. Asserts the CORRECT shape for every type
 * kind and constructor/property form, so a lowering regression (or bug) surfaces here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinFrontendTest {
    private lateinit var fixture: SessionFixture
    private lateinit var kinds: SourceUnit

    @BeforeAll
    fun setUp() {
        val graph = SessionFixture.singleModule(srcRoots = listOf("src/main/kotlin", "src/main/java"))
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/metrics"))
        kinds = fixture.kotlinUnit("Kinds.kt")
    }

    @AfterAll
    fun tearDown() = fixture.close()

    @Test
    fun `the default constructor selects the name-based classifier`() {
        // Omitting the `resolved` argument exercises its default (false) → the syntactic KotlinClassifier.
        val frontend = KotlinFrontend(repoRoot().resolve("testdata/metrics"))
        frontend.classifier.shouldBeInstanceOf<KotlinClassifier>()
        frontend.lang shouldBe Lang.KOTLIN
    }

    @Test
    fun `each declaration lowers to its type kind`() {
        kinds.type("Speakable").kind shouldBe TypeKind.INTERFACE
        kinds.type("Color").kind shouldBe TypeKind.ENUM
        kinds.type("Singleton").kind shouldBe TypeKind.OBJECT
        kinds.type("Marker").kind shouldBe TypeKind.ANNOTATION
        kinds.type("Shape2").kind shouldBe TypeKind.SEALED
        kinds.type("Rich").kind shouldBe TypeKind.CLASS
    }

    @Test
    fun `an interface and an annotation are abstract`() {
        kinds.type("Speakable").isAbstract shouldBe true
        kinds.type("Marker").isAbstract shouldBe true
        kinds.type("Rich").isAbstract shouldBe false
    }

    @Test
    fun `enum entries are NOT lowered as nested types`() {
        // KtEnumEntry extends KtClass; a naive walk would turn RED/GREEN/BLUE into spurious nested classes.
        kinds.type("Color").nested shouldBe emptyList()
    }

    @Test
    fun `a sealed hierarchy lowers its members as nested classes`() {
        val shape = kinds.type("Shape2")
        shape.nested.map { it.name } shouldContain "Circle"
        shape.nested.map { it.name } shouldContain "Square"
        shape.nested.first { it.name == "Circle" }.kind shouldBe TypeKind.CLASS
    }

    @Test
    fun `primary val params and body properties both become fields`() {
        val rich = kinds.type("Rich")
        val fieldNames = rich.fields.map { it.name }
        fieldNames shouldContain "id" // primary-constructor `val`
        fieldNames shouldContain "derived" // body `val`
        fieldNames shouldContain "counter" // body `var`
        // `name` is a plain primary-constructor param (no val/var) → NOT a field.
        fieldNames shouldNotContain "name"
    }

    @Test
    fun `a secondary constructor lowers as an init method`() {
        val rich = kinds.type("Rich")
        rich.methods.any { it.isConstructor && it.name == "<init>" } shouldBe true
        rich.methods.any { it.name == "method" } shouldBe true
    }

    @Test
    fun `nested types are lowered and reachable via the unit walk`() {
        kinds.type("Rich").nested.map { it.name } shouldContain "Nested"
        kinds.allTypes().map { it.name }.toList() shouldContain "Nested"
    }

    @Test
    fun `properties carry their declared type text`() {
        val rich = kinds.type("Rich")
        rich.fields.first { it.name == "derived" }.typeName shouldBe "String"
        rich.fields.all { it.isProperty } shouldBe true
    }
}
