package dev.ktrics.frontend.java

import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TokenKind
import dev.ktrics.langapi.NodeClassifier
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
 * Direct unit coverage for the Java `NodeClassifier` dispatch in its HOME module. Mirror
 * of [KotlinClassifierTest]: the Java-specific counting (notably the polyadic `&&` node whose decision
 * weight is the operator count) is pinned here over a real session, instead of only being credited to
 * the metric/engine modules that exercise it through the calculators.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaClassifierTest {
    private lateinit var fixture: SessionFixture
    private lateinit var shapes: SourceUnit
    private lateinit var coupling: SourceUnit
    private lateinit var classifier: NodeClassifier

    @BeforeAll
    fun setUp() {
        val graph = SessionFixture.singleModule(srcRoots = listOf("src/main/kotlin", "src/main/java"))
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/metrics"))
        shapes = fixture.javaUnit("JShapes.java")
        coupling = fixture.javaUnit("JCoupling.java")
        classifier = fixture.classifier(Lang.JAVA)
    }

    @AfterAll
    fun tearDown() = fixture.close()

    private fun decisionWeight(fnName: String): Int {
        val body = shapes.function(fnName).bodyNode!!
        return classifier.descendants(body).sumOf { classifier.decisionWeight(it) }
    }

    @Test
    fun `a polyadic AND node weighs the operator count in java`() {
        // `if (a && b && c)`: the one polyadic `&&` node weighs 2, plus the if → decision weight 3,
        // matching Kotlin's two separate `&&` nodes (doc/calibration.md).
        decisionWeight("polyadicAnd") shouldBe 3
    }

    @Test
    fun `each non-default switch case is a decision`() {
        decisionWeight("whenFour") shouldBe 3
    }

    @Test
    fun `nested ifs each contribute a decision and a nesting boundary`() {
        decisionWeight("nestedTwo") shouldBe 2
        val body = shapes.function("nestedTwo").bodyNode!!
        classifier.descendants(body).count { classifier.isNestingBoundary(it) } shouldBe 2
    }

    @Test
    fun `straight-line code has no decisions`() {
        decisionWeight("straight") shouldBe 0
    }

    @Test
    fun `java parameters never carry defaults and boolean flags are detected`() {
        val four = classifier.parameters(shapes.function("fourParams").node)
        four.size shouldBe 4
        four.none { it.hasDefault } shouldBe true

        val booleans = classifier.parameters(shapes.function("threeBooleans").node)
        booleans.size shouldBe 3
        booleans.all { it.isBoolean } shouldBe true
    }

    @Test
    fun `tokens classify operators and operands`() {
        val body = shapes.function("straight").bodyNode!!
        val tokens = classifier.tokens(body)
        tokens.any { it.kind == TokenKind.OPERAND } shouldBe true
        tokens.any { it.kind == TokenKind.OPERATOR } shouldBe true
    }

    @Test
    fun `scope-function invocation is always false for java`() {
        val body = shapes.function("polyadicAnd").node
        classifier.descendants(body).none { classifier.isScopeFunctionInvocation(it) } shouldBe true
    }

    // --- npath / loop helpers ---

    @Test
    fun `loop nodes are recognised as loop-or-branch`() {
        val body = shapes.function("loops").bodyNode!!
        classifier.descendants(body).count { classifier.isLoopOrBranch(it) } shouldBe 2 // for + while
    }

    @Test
    fun `npath multiplier is two per loop and the label count for a switch`() {
        val loops = shapes.function("loops").bodyNode!!
        classifier.descendants(loops)
            .filter { classifier.isLoopOrBranch(it) }
            .forEach { classifier.npathMultiplier(it) shouldBe 2 }

        val switchBody = shapes.function("whenFour").bodyNode!!
        val switchNode = classifier.descendants(switchBody).first { classifier.isLoopOrBranch(it) }
        // case 0 / 1 / 2 / default → 4 labels.
        classifier.npathMultiplier(switchNode) shouldBe 4
    }

    @Test
    fun `a polyadic logical expression is a single logical sequence`() {
        val body = shapes.function("polyadicAnd").bodyNode!!
        classifier.descendants(body).count { classifier.isLogicalSequence(it) } shouldBe 1
    }

    // --- reference extraction (name-based base classifier) ---

    @Test
    fun `called symbols collect the invoked methods`() {
        val first = coupling.function("first").bodyNode!!
        val names = classifier.calledSymbols(first).map { it.name }
        names shouldContain "use" // dep.use()
        names shouldContain "second" // sibling call
    }

    @Test
    fun `referenced types collect the type references in scope`() {
        classifier.referencedTypes(coupling.type("JCoupled").node).map { it.name } shouldContain "JDep"
    }

    @Test
    fun `outgoing ref names merge invoked methods and referenced type names`() {
        // The call-graph feed: the flat union of method-call names and type references in one scope.
        val names = classifier.outgoingRefNames(coupling.function("first").bodyNode!!)
        names shouldContain "use" // dep.use()
        names shouldContain "second" // sibling call
    }

    @Test
    fun `referenced names include value-level reads invisible to call and type extraction`() {
        // JCoupled.first() reads field `dep` before invoking use(): the read is no call and no type
        // position, so only referencedNames sees it (the unused detector's value-read channel).
        val names = classifier.referencedNames(coupling.function("first").bodyNode!!)
        names shouldContain "dep"
        names shouldContain "use"
    }

    @Test
    fun `supertypes list the extends and implements references`() {
        val names = classifier.supertypes(coupling.type("JCoupled").node).map { it.name }
        names shouldContain "JBase"
        names shouldContain "JGreeter"
    }

    @Test
    fun `field accesses inside a method are detected`() {
        classifier.fieldAccesses(coupling.function("first").bodyNode!!) shouldContain "dep"
    }

    @Test
    fun `modifiers derive isOverride from an Override annotation`() {
        // Java's @Override is an annotation, not a modifier keyword, so modifiers() must scan the method's
        // annotations to set isOverride — JGhostChild.toString() carries @Override.
        val unresolved = fixture.javaUnit("JUnresolved.java")
        val toString = unresolved.type("JGhostChild").methods.first { it.name == "toString" }
        classifier.modifiers(toString.node).isOverride shouldBe true
    }

    @Test
    fun `text returns the source of a node`() {
        classifier.text(shapes.function("straight").node).contains("straight") shouldBe true
    }

    @Test
    fun `else-if branches charge flat and plain else rides the owning if`() {
        val body = shapes.function("elseIfChain").bodyNode!!
        // Two `else if` links are flat increments; the final plain else charges +1 through its if.
        classifier.descendants(body).count { classifier.isFlatIncrement(it) } shouldBe 2
        classifier.descendants(body).sumOf { classifier.elseIncrement(it) } shouldBe 1
    }

    @Test
    fun `a labeled continue is a flat increment`() {
        val body = shapes.function("labeledJump").bodyNode!!
        classifier.descendants(body).count { classifier.isFlatIncrement(it) } shouldBe 1
    }

    @Test
    fun `lambdas are nesting-only boundaries and argument closures are recognised`() {
        val body = shapes.function("lambdaNesting").bodyNode!!
        classifier.descendants(body).count { classifier.isNestingOnlyBoundary(it) } shouldBe 1
        classifier.descendants(body).count { classifier.isArgumentClosure(it) } shouldBe 1
        // A lambda is no longer a FULL nesting boundary — it raises depth without a B1 increment.
        classifier.descendants(body).count { classifier.isNestingBoundary(it) } shouldBe 1 // the if only
    }
}
