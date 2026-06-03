package dev.ktrics.frontend.java

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SourceUnit
import dev.ktrics.testsession.SessionFixture
import dev.ktrics.testsession.function
import dev.ktrics.testsession.repoRoot
import dev.ktrics.testsession.type
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Coverage for [ResolvedJavaClassifier]'s `.resolve()`-backed edges — the resolution
 * feature the syntactic [JavaClassifierTest] does not reach. Over a real session the coupling/
 * inheritance lenses return owner-qualified, RESOLVED references.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResolvedJavaClassifierTest {
    private lateinit var fixture: SessionFixture
    private lateinit var coupling: SourceUnit
    private lateinit var unresolved: SourceUnit
    private lateinit var classifier: ResolvedJavaClassifier

    @BeforeAll
    fun setUp() {
        val graph = SessionFixture.singleModule(srcRoots = listOf("src/main/kotlin", "src/main/java"))
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/metrics"), resolved = true)
        coupling = fixture.javaUnit("JCoupling.java")
        unresolved = fixture.javaUnit("JUnresolved.java")
        classifier = fixture.classifier(Lang.JAVA) as ResolvedJavaClassifier
    }

    @AfterAll
    fun tearDown() = fixture.close()

    @Test
    fun `called methods resolve to their owner-qualified key`() {
        val keys = classifier.calledSymbols(coupling.function("first").bodyNode!!).map { it.key }
        keys shouldContain "calib.JDep.use"
        keys shouldContain "calib.JCoupled.second"
    }

    @Test
    fun `outgoing ref names resolve method calls to their owner-qualified key`() {
        val names = classifier.outgoingRefNames(coupling.function("first").bodyNode!!)
        names shouldContain "calib.JDep.use"
        names shouldContain "calib.JCoupled.second"
    }

    @Test
    fun `referenced types resolve to qualified, RESOLVED refs`() {
        val refs = classifier.referencedTypes(coupling.type("JCoupled").node)
        val dep = refs.first { it.name == "JDep" }
        dep.resolution shouldBe Resolution.RESOLVED
        dep.qualifiedName shouldBe "calib.JDep"
    }

    @Test
    fun `an unresolvable call degrades to a name-based symbol ref and bare outgoing name`() {
        val body = unresolved.function("mixedCalls").bodyNode!!
        // ghostMethod() resolves to nothing → name-based SymbolRef and a bare outgoing-ref name.
        classifier.calledSymbols(body).first { it.name == "ghostMethod" }.resolution shouldBe Resolution.NAME_BASED
        classifier.outgoingRefNames(body) shouldContain "ghostMethod"
    }

    @Test
    fun `an unresolvable type degrades to a name-based ref`() {
        val body = unresolved.function("useGhost").bodyNode!!
        // GhostType resolves to nothing → name-based TypeRef (nameRef) with no qualified name...
        val ghost = classifier.referencedTypes(body).first { it.name == "GhostType" }
        ghost.resolution shouldBe Resolution.NAME_BASED
        ghost.qualifiedName shouldBe null
        // ...and outgoingRefNames degrades the same type to its bare presentable name.
        classifier.outgoingRefNames(body) shouldContain "GhostType"
    }

    @Test
    fun `a call into an anonymous class member degrades to the bare method name`() {
        // helper() resolves to a method on the anonymous Runnable (no qualified owner) → bare name.
        classifier.outgoingRefNames(unresolved.function("anon").bodyNode!!) shouldContain "helper"
    }

    @Test
    fun `supertypes resolve to the extends and implements targets, excluding Object`() {
        val supers = classifier.supertypes(coupling.type("JCoupled").node)
        supers.map { it.qualifiedName } shouldContain "calib.JBase"
        supers.map { it.qualifiedName } shouldContain "calib.JGreeter"
        supers.none { it.qualifiedName == "java.lang.Object" } shouldBe true
    }

    @Test
    fun `an unresolvable supertype degrades to a name-based ref`() {
        // JGhostChild extends GhostBase implements GhostMixin, neither of which exists: ref.resolve() is
        // null for both, so supertypes() takes the else branch and emits NAME_BASED refs by syntactic name.
        val supers = classifier.supertypes(unresolved.type("JGhostChild").node)
        supers.first { it.name == "GhostBase" }.resolution shouldBe Resolution.NAME_BASED
        supers.first { it.name == "GhostMixin" }.resolution shouldBe Resolution.NAME_BASED
    }
}
