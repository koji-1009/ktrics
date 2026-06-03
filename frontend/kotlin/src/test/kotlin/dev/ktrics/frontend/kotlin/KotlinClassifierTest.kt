package dev.ktrics.frontend.kotlin

import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
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
 * Direct unit coverage for the Kotlin `NodeClassifier` dispatch in its HOME module.
 * The classifier is exercised end-to-end by the metric/engine tests, but that coverage is credited to
 * those modules; this pins the Kotlin-specific counting (decision points, scope functions, `!!` tokens,
 * defaulted/boolean parameters, field accesses) where it lives, over a real session.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinClassifierTest {
    private lateinit var fixture: SessionFixture
    private lateinit var shapes: SourceUnit
    private lateinit var coupling: SourceUnit
    private lateinit var classifier: NodeClassifier

    @BeforeAll
    fun setUp() {
        val graph = SessionFixture.singleModule(srcRoots = listOf("src/main/kotlin", "src/main/java"))
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/metrics"))
        shapes = fixture.kotlinUnit("KShapes.kt")
        coupling = fixture.kotlinUnit("Coupling.kt")
        classifier = fixture.classifier(Lang.KOTLIN)
    }

    @AfterAll
    fun tearDown() = fixture.close()

    /** Total decision weight over a function body equals cyclomatic minus the base 1. */
    private fun decisionWeight(fnName: String): Int {
        val body = shapes.function(fnName).bodyNode!!
        return classifier.descendants(body).sumOf { classifier.decisionWeight(it) }
    }

    @Test
    fun `each chained AND counts as its own decision in kotlin`() {
        // `if (a && b && c)`: the if plus two `&&` nodes → decision weight 3.
        decisionWeight("polyadicAnd") shouldBe 3
    }

    @Test
    fun `each non-else when entry is a decision`() {
        // `when` with three non-else entries → decision weight 3.
        decisionWeight("whenFour") shouldBe 3
    }

    @Test
    fun `nested ifs each contribute a decision and a nesting boundary`() {
        decisionWeight("nestedTwo") shouldBe 2
        val body = shapes.function("nestedTwo").bodyNode!!
        classifier.descendants(body).count { classifier.isNestingBoundary(it) } shouldBe 2
    }

    @Test
    fun `straight-line code has no decisions or nesting`() {
        decisionWeight("straight") shouldBe 0
        val body = shapes.function("straight").bodyNode!!
        classifier.descendants(body).count { classifier.isNestingBoundary(it) } shouldBe 0
    }

    @Test
    fun `scope-function invocations are recognised`() {
        // nestedScopes nests `let` inside `let` → two scope-function invocations in the body.
        val body = shapes.function("nestedScopes").bodyNode!!
        classifier.descendants(body).count { classifier.isScopeFunctionInvocation(it) } shouldBe 2
    }

    @Test
    fun `not-null assertions surface as tokens`() {
        val body = shapes.function("bangBang").bodyNode!!
        classifier.tokens(body).count { it.text == "!!" } shouldBe 2
    }

    @Test
    fun `parameters expose defaults, varargs and boolean flags`() {
        val four = classifier.parameters(shapes.function("fourParams").node)
        four.size shouldBe 4
        four.none { it.hasDefault } shouldBe true

        val defaulted = classifier.parameters(shapes.function("twoDefaulted").node)
        defaulted[0].hasDefault shouldBe false
        defaulted[1].hasDefault shouldBe true
        defaulted[2].hasDefault shouldBe true

        val booleans = classifier.parameters(shapes.function("threeBooleans").node)
        booleans.all { it.isBoolean } shouldBe true
    }

    @Test
    fun `modifiers normalise kotlin default visibility to public`() {
        classifier.modifiers(shapes.type("KShapes").node).visibility shouldBe dev.ktrics.ir.Visibility.PUBLIC
    }

    @Test
    fun `field accesses inside a method are detected`() {
        // Coupled.first() reads and writes `counter`.
        val first = coupling.function("first").bodyNode!!
        classifier.fieldAccesses(first) shouldContain "counter"
    }

    @Test
    fun `tokens classify operators and operands`() {
        val body = shapes.function("straight").bodyNode!!
        val tokens = classifier.tokens(body)
        // `val a = 1; val b = 2; return a + b` has both operands (a, b, 1, 2) and operators (=, +).
        tokens.any { it.kind == dev.ktrics.ir.TokenKind.OPERAND } shouldBe true
        tokens.any { it.kind == dev.ktrics.ir.TokenKind.OPERATOR } shouldBe true
    }

    @Test
    fun `comment and whitespace nodes are skipped by sloc`() {
        // The classifier flags whitespace/comment PSI; at least the leading whitespace of a body qualifies.
        val body = shapes.function("straight").bodyNode!!
        classifier.descendants(body).any { classifier.isCommentOrWhitespace(it) } shouldBe true
    }

    // --- npath / loop helpers ---

    @Test
    fun `loop nodes are recognised as loop-or-branch`() {
        val body = shapes.function("loops").bodyNode!!
        // The for and the while are the loop-or-branch nodes.
        classifier.descendants(body).count { classifier.isLoopOrBranch(it) } shouldBe 2
    }

    @Test
    fun `npath multiplier is two per loop and the entry count for when`() {
        val loops = shapes.function("loops").bodyNode!!
        classifier.descendants(loops)
            .filter { classifier.isLoopOrBranch(it) }
            .forEach { classifier.npathMultiplier(it) shouldBe 2 }

        val whenBody = shapes.function("whenFour").bodyNode!!
        // The expression body IS the `when`, so include the root node itself in the search.
        val whenNode =
            (sequenceOf(whenBody) + classifier.descendants(whenBody))
                .first { classifier.isLoopOrBranch(it) }
        classifier.npathMultiplier(whenNode) shouldBe 4 // 0, 1, 2, else
    }

    @Test
    fun `a run of like logical operators counts once`() {
        val body = shapes.function("polyadicAnd").bodyNode!!
        // `a && b && c` is a single run of `&&`, so exactly one logical sequence.
        classifier.descendants(body).count { classifier.isLogicalSequence(it) } shouldBe 1
    }

    // --- reference extraction (name-based base classifier) ---

    @Test
    fun `called symbols collect the invoked callees`() {
        val first = coupling.function("first").bodyNode!!
        classifier.calledSymbols(first).map { it.name } shouldContain "second"
    }

    @Test
    fun `referenced types collect the type references in scope`() {
        classifier.referencedTypes(coupling.type("Coupled").node).map { it.name } shouldContain "Dep1"
    }

    @Test
    fun `outgoing ref names merge invoked callees and referenced type names`() {
        // The flat call-graph feed (callee names ++ type-reference names) over one scope.
        val names = classifier.outgoingRefNames(coupling.function("first").bodyNode!!)
        names shouldContain "second"
    }

    @Test
    fun `supertypes list the declared extends and implements`() {
        val names = classifier.supertypes(coupling.type("Polite").node).map { it.name }
        names shouldContain "Base"
        names shouldContain "Greeter"
    }

    @Test
    fun `text returns the source of a node`() {
        classifier.text(shapes.function("straight").node).contains("straight") shouldBe true
    }
}
