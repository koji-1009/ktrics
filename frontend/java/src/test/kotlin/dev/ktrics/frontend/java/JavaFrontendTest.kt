package dev.ktrics.frontend.java

import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeKind
import dev.ktrics.testsession.SessionFixture
import dev.ktrics.testsession.repoRoot
import dev.ktrics.testsession.type
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Lowering a Java file to the normalized IR. Java method bodies come from Java PSI, and
 * every type kind (class/interface/enum/record/annotation) must lower correctly — asserted here so a
 * lowering bug surfaces.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaFrontendTest {
    private lateinit var fixture: SessionFixture
    private lateinit var kinds: SourceUnit

    @BeforeAll
    fun setUp() {
        val graph = SessionFixture.singleModule(srcRoots = listOf("src/main/kotlin", "src/main/java"))
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/metrics"))
        kinds = fixture.javaUnit("JKinds.java")
    }

    @AfterAll
    fun tearDown() = fixture.close()

    @Test
    fun `the default constructor selects the name-based classifier`() {
        // Omitting the `resolved` argument exercises its default (false) → the syntactic JavaClassifier.
        val frontend = JavaFrontend(repoRoot().resolve("testdata/metrics"))
        frontend.classifier.shouldBeInstanceOf<JavaClassifier>()
        frontend.lang shouldBe Lang.JAVA
    }

    @Test
    fun `each declaration lowers to its type kind`() {
        kinds.type("JSpeakable").kind shouldBe TypeKind.INTERFACE
        kinds.type("JColor").kind shouldBe TypeKind.ENUM
        kinds.type("JMarker").kind shouldBe TypeKind.ANNOTATION
        kinds.type("JPoint").kind shouldBe TypeKind.RECORD
        kinds.type("JRich").kind shouldBe TypeKind.CLASS
    }

    @Test
    fun `a record is data-like and interfaces or annotations are abstract`() {
        kinds.type("JPoint").isDataLike shouldBe true
        kinds.type("JSpeakable").isAbstract shouldBe true
        kinds.type("JMarker").isAbstract shouldBe true
        kinds.type("JRich").isAbstract shouldBe false
    }

    @Test
    fun `fields and methods lower with the constructor flagged`() {
        val rich = kinds.type("JRich")
        rich.fields.map { it.name } shouldContain "id"
        rich.fields.map { it.name } shouldContain "counter"
        rich.fields.all { !it.isProperty } shouldBe true // Java fields are not Kotlin properties
        rich.methods.any { it.name == "method" } shouldBe true
        rich.methods.any { it.isConstructor } shouldBe true
    }

    @Test
    fun `a java method body materializes from PSI with real content`() {
        // A Java method's body is lowered with actual statements, not just its signature.
        // JRich.method() is `{ return counter; }`.
        val method = kinds.type("JRich").methods.first { it.name == "method" }
        method.bodyNode.shouldNotBeNull()
        val body = method.bodyNode!!
        body shouldNotBe method.node // the body block is a distinct sub-node, not the whole declaration
        fixture.classifier(Lang.JAVA).text(body).contains("return counter") shouldBe true
    }

    @Test
    fun `a static nested class is lowered`() {
        kinds.type("JRich").nested.map { it.name } shouldContain "JNested"
    }

    @Test
    fun `a java file has no top-level functions or properties`() {
        kinds.topLevelFns shouldBe emptyList()
        kinds.topLevelProps shouldBe emptyList()
    }
}
