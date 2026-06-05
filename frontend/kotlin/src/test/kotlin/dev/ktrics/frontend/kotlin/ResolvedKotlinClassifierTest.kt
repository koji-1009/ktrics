package dev.ktrics.frontend.kotlin

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SourceUnit
import dev.ktrics.testsession.SessionFixture
import dev.ktrics.testsession.function
import dev.ktrics.testsession.repoRoot
import dev.ktrics.testsession.type
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Coverage for [ResolvedKotlinClassifier]'s `analyze {}`-backed edges — the whole resolution feature,
 * which the syntactic [KotlinClassifierTest] does not reach. Over a real resolved session
 * the coupling/inheritance lenses return owner-qualified, RESOLVED references, not bare names.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResolvedKotlinClassifierTest {
    private lateinit var fixture: SessionFixture
    private lateinit var coupling: SourceUnit
    private lateinit var unresolved: SourceUnit
    private lateinit var classifier: ResolvedKotlinClassifier

    @BeforeAll
    fun setUp() {
        val graph = SessionFixture.singleModule(srcRoots = listOf("src/main/kotlin", "src/main/java"))
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/metrics"), resolved = true)
        coupling = fixture.kotlinUnit("Coupling.kt")
        unresolved = fixture.kotlinUnit("Unresolved.kt")
        operators = fixture.kotlinUnit("Operators.kt")
        classifier = fixture.classifier(Lang.KOTLIN) as ResolvedKotlinClassifier
    }

    private lateinit var operators: SourceUnit

    @AfterAll
    fun tearDown() = fixture.close()

    @Test
    fun `operator forms resolve to the user-defined convention functions they invoke`() {
        // `a + b` / `a[0]` / `1 in a` / `a()` carry no name reference to plus/get/contains/invoke —
        // the dartrics 0.7.3 false-positive class. The resolved classifier must record each edge.
        val keys = classifier.calledSymbols(operators.function("useThem").bodyNode!!).map { it.key }
        keys shouldContain "calib.Vec.plus"
        keys shouldContain "calib.Vec.get"
        keys shouldContain "calib.Vec.contains"
        keys shouldContain "calib.Vec.invoke"
    }

    @Test
    fun `operator forms feed the call graph as exact resolved keys`() {
        // `a + b` attributes fan-in to the exact plus it targets (the dartrics 0.7.3 signals fix).
        val names = classifier.outgoingRefNames(operators.function("useThem").bodyNode!!)
        names shouldContain "calib.Vec.plus"
        names shouldContain "calib.Vec.get"
    }

    @Test
    fun `a compound unary form degrades to its bare convention name`() {
        // `v++` resolves as a compound access (not a simple function call), so the call-graph feed
        // falls back to the bare convention name — the same shape the name-based classifier emits.
        val names = classifier.outgoingRefNames(operators.function("useUnary").bodyNode!!)
        names shouldContain "inc"
    }

    @Test
    fun `stdlib operator targets are not recorded as calls`() {
        // `x + other.x` resolves to kotlin.Int.plus — built-in arithmetic, not a message (same
        // philosophy as CBO's primitive exclusion); recording it would inflate RFC on every method.
        val keys = classifier.calledSymbols(operators.function("plus").bodyNode!!).map { it.key }
        keys.none { it.startsWith("kotlin.") } shouldBe true
    }

    @Test
    fun `destructuring, iteration, and delegation emit their convention names for reachability`() {
        // These forms ride the name-based referencedNames channel (only ever ADDS reachability,
        // never feeds the resolution stamp), so component1/iterator/getValue stay alive.
        classifier.referencedNames(operators.function("useThem").node) shouldContain "component1"
        val loop = classifier.referencedNames(operators.function("loopAndDelegate").node)
        loop shouldContain "iterator"
        loop shouldContain "hasNext"
        loop shouldContain "next"
        loop shouldContain "getValue"
    }

    @Test
    fun `operator-only members are kept out of the unused report`() {
        // End-to-end: main → useThem/loopAndDelegate reach every Vec operator ONLY via operator
        // forms; none may surface as unused (--apply would otherwise delete live code).
        val units = listOf(operators)
        val report = dev.ktrics.unused.UnusedDetector(units, { classifier }).detect()
        val unusedKeys = report.unused.map { it.key }
        unusedKeys.none { it.startsWith("calib.Vec.") } shouldBe true
        unusedKeys shouldNotContain "calib.Vec"
    }

    @Test
    fun `an unresolvable type degrades to a name-based ref and a shadowed primitive is dropped`() {
        // GhostType does not exist → the edge degrades to its written text, NAME_BASED.
        val ghostRefs = classifier.referencedTypes(unresolved.function("unresolvedType").node)
        ghostRefs.single { it.name == "GhostType" }.resolution shouldBe Resolution.NAME_BASED
        // ShadowedPrimitive<Int>: `Int` resolves to the TYPE PARAMETER, not a class — it must be
        // dropped rather than emitted as a spurious primitive coupling.
        val shadowed = classifier.referencedTypes(unresolved.type("ShadowedPrimitive").node)
        shadowed.none { it.name == "Int" } shouldBe true
    }

    @Test
    fun `an exhaustive when over a sealed subject contributes no entry decisions`() {
        // Sealed dispatch is compiler-enforced enumeration (the dartrics sealed-switch rule):
        // resolved-mode cyclomatic weighs the entries 0 → the function's only decision weight is 0.
        val sealed = fixture.kotlinUnit("Sealed.kt")
        val body = sealed.function("sealedDispatch").bodyNode!!
        val weight = (sequenceOf(body) + classifier.descendants(body)).sumOf { classifier.decisionWeight(it) }
        weight shouldBe 0
    }

    @Test
    fun `a when over a non-sealed subject keeps its entry decisions in resolved mode`() {
        val shapes = fixture.kotlinUnit("KShapes.kt")
        val body = shapes.function("whenFour").bodyNode!!
        val weight = (sequenceOf(body) + classifier.descendants(body)).sumOf { classifier.decisionWeight(it) }
        weight shouldBe 3 // three non-else entries; Int is not sealed
    }

    @Test
    fun `referenced types resolve to qualified, RESOLVED refs`() {
        val refs = classifier.referencedTypes(coupling.type("Coupled").node)
        val dep1 = refs.first { it.name == "Dep1" }
        dep1.resolution shouldBe Resolution.RESOLVED
        dep1.qualifiedName shouldBe "calib.Dep1"
    }

    @Test
    fun `called symbols resolve to their owner-qualified key`() {
        val calls = classifier.calledSymbols(coupling.function("first").bodyNode!!)
        // first() calls second(); the resolved key carries the declaring class.
        calls.map { it.key } shouldContain "calib.Coupled.second"
    }

    @Test
    fun `outgoing ref names resolve calls to their owner-qualified callable key`() {
        // The resolved call-graph feed runs each edge through callableKey: a member call becomes
        // `Owner.method` so the graph matches the exact target, not every same-named declaration.
        val names = classifier.outgoingRefNames(coupling.function("first").bodyNode!!)
        names shouldContain "calib.Coupled.second"
    }

    @Test
    fun `a Kotlin-to-Java call resolves to the Java owner's qualified key`() {
        // The headline cross-language feature: a Kotlin call into a Java method is keyed by the JAVA owner,
        // proving the resolved classifier resolves across the language boundary.
        val names = classifier.outgoingRefNames(unresolved.function("useJava").bodyNode!!)
        names shouldContain "calib.JDep.use"
    }

    @Test
    fun `an unresolvable call degrades to a name-based symbol ref`() {
        val refs = classifier.calledSymbols(unresolved.function("mixedCalls").bodyNode!!)
        // ghostFunction() resolves to nothing → the edge degrades to its bare, name-based form.
        refs.first { it.name == "ghostFunction" }.resolution shouldBe Resolution.NAME_BASED
    }

    @Test
    fun `an unresolvable type reference degrades to a name-based type ref`() {
        // GhostType is undefined → its KaType is an error type with no expanded class symbol, so the
        // resolved path can't qualify it and the edge degrades to a name-based ref.
        val ghost = classifier.referencedTypes(unresolved.function("unresolvedType").node).filter { it.name.startsWith("GhostType") }
        ghost.shouldNotBeEmpty()
        ghost.forEach {
            it.qualifiedName shouldBe null // the property the calculators consume: an unresolved type has none
            it.resolution shouldBe Resolution.NAME_BASED
        }
    }

    @Test
    fun `a type parameter shadowing a primitive name is dropped, not coupled`() {
        // `Int` here is a type parameter (no expanded class symbol), but its simple name is a primitive
        // name — so the resolved classifier drops it rather than emitting a spurious coupling to it.
        val node = unresolved.type("ShadowedPrimitive").node
        classifier.referencedTypes(node).none { it.name == "Int" } shouldBe true
    }

    @Test
    fun `outgoing ref names key a resolved top-level call by package and fall back for an unresolved one`() {
        val names = classifier.outgoingRefNames(unresolved.function("mixedCalls").bodyNode!!)
        names shouldContain "calib.topLevelHelper" // callableKey's package-qualified (no owner class) path
        names shouldContain "ghostFunction" // unresolved → bare name
    }

    @Test
    fun `outgoingRefNames preserves call multiplicity while calledSymbols dedups by key`() {
        // callsBoth calls `run` twice. The two APIs have different contracts (NodeClassifier docs): the
        // *Calls fan-out signals need multiplicity, the reachability edges need distinct keys.
        val body = unresolved.function("callsBoth").bodyNode!!
        val base = KotlinClassifier()
        base.outgoingRefNames(body).count { it == "run" } shouldBe 2 // multiplicity kept
        base.calledSymbols(body).count { it.name == "run" } shouldBe 1 // deduped by key
    }

    @Test
    fun `resolution disambiguates a homonym call by owner where name-based collapses it`() {
        // The defining property of resolution: HomonymA.run() and HomonymB.run() are different edges.
        val body = unresolved.function("callsBoth").bodyNode!!
        val resolved = classifier.outgoingRefNames(body)
        resolved shouldContain "calib.HomonymA.run"
        resolved shouldContain "calib.HomonymB.run"
        // The syntactic (name-based) base classifier, on the SAME body, collapses both to the bare name.
        val nameBased = KotlinClassifier().outgoingRefNames(body)
        nameBased.count { it == "run" } shouldBe 2
        nameBased.none { it.contains(".run") } shouldBe true
    }

    @Test
    fun `a nullable type ref is normalized to its plain class, not kept as Dep1-question-mark`() {
        // This was finding #3: the name-based path used to key a nullable as `Dep1?` — a DISTINCT key from
        // `Dep1`, over-counting couplings in name-based mode. NOW FIXED: the `?` is stripped so both
        // classifiers key the nullable by its plain class (no collision with the non-nullable form).
        val node = coupling.type("Generic").node
        val nameBased = KotlinClassifier().referencedTypes(node).map { it.name }
        nameBased shouldContain "Dep1"
        nameBased.none { it.contains("?") } shouldBe true
        classifier.referencedTypes(node).first { it.name == "Dep1" }.qualifiedName shouldBe "calib.Dep1"
    }

    @Test
    fun `a generic type ref keys by the bare container in both classifiers`() {
        val node = coupling.type("Generic").node
        // name-based: the generic argument list is dropped (`List<Dep1>` → `List`)...
        val nameBased = KotlinClassifier().referencedTypes(node).map { it.name }
        nameBased shouldContain "List"
        nameBased.none { it.contains("<") } shouldBe true
        // ...and resolved keys the container and the argument by their qualified names.
        val resolved = classifier.referencedTypes(node).mapNotNull { it.qualifiedName }
        resolved shouldContain "kotlin.collections.List"
        resolved shouldContain "calib.Dep1"
    }

    @Test
    fun `supertypes resolve through the symbol space, excluding Any`() {
        val supers = classifier.supertypes(coupling.type("Polite").node)
        val keys = supers.map { it.qualifiedName }
        keys shouldContain "calib.Base"
        keys shouldContain "calib.Greeter"
        // kotlin.Any is filtered out of the resolved supertypes.
        supers.none { it.qualifiedName == "kotlin.Any" } shouldBe true
    }
}
