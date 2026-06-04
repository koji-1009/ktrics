package dev.ktrics.metric.clazz

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.ProjectIndex
import dev.ktrics.testsession.SessionFixture
import dev.ktrics.testsession.repoRoot
import dev.ktrics.testsession.type
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Unit coverage for the CK class-level calculators in their HOME module. These were
 * previously exercised only through `:engine` integration tests, so JaCoCo credited the coverage to
 * engine, not metric; this pins their behaviour where they live. Index-free lenses (NOM/WMC/CBO/RFC/
 * ClassLength/LCOM4) run over a real session; the inheritance lenses (DIT/NOC) use a fake index.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CkCalculatorTest {
    private lateinit var fixture: SessionFixture
    private lateinit var unit: SourceUnit
    private lateinit var javaUnit: SourceUnit
    private lateinit var unresolvedUnit: SourceUnit
    private lateinit var nameBasedFixture: SessionFixture
    private lateinit var nameBasedUnit: SourceUnit

    @BeforeAll
    fun setUp() {
        val graph = SessionFixture.singleModule(srcRoots = listOf("src/main/kotlin", "src/main/java"))
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/metrics"), resolved = true)
        unit = fixture.kotlinUnit("Coupling.kt")
        javaUnit = fixture.javaUnit("JCoupling.java")
        unresolvedUnit = fixture.kotlinUnit("Unresolved.kt")
        nameBasedFixture = SessionFixture(graph, repoRoot().resolve("testdata/metrics"), resolved = false)
        nameBasedUnit = nameBasedFixture.kotlinUnit("Coupling.kt")
    }

    @AfterAll
    fun tearDown() {
        fixture.close()
        nameBasedFixture.close()
    }

    private fun ctx(index: ProjectIndex? = null) = MeasureContext(fixture.classifier(unit.lang), unit, index)

    private fun javaCtx(index: ProjectIndex? = null) = MeasureContext(fixture.classifier(Lang.JAVA), javaUnit, index)

    @Test
    fun `number-of-methods counts the declared methods`() {
        NumberOfMethods().measure(unit.type("Coupled"), ctx()) shouldBe 3.0
    }

    @Test
    fun `weighted-methods-per-class sums the per-method cyclomatic complexity`() {
        // first/second/third each have cyclomatic 1 → WMC 3.
        WeightedMethodsPerClass().measure(unit.type("Coupled"), ctx()) shouldBe 3.0
    }

    @Test
    fun `class-length is the declaration line span`() {
        // The KtClass text range includes its leading KDoc, so Coupled spans lines 13-31 → 19 lines.
        ClassLength().measure(unit.type("Coupled"), ctx()) shouldBe 19.0
    }

    @Test
    fun `coupling-between-objects counts distinct referenced CLASSES, excluding primitives`() {
        // Coupled references Dep1, Dep2 and the builtin Int. Int is a primitive, not a class coupling, so
        // CBO excludes it (and the self-type) → exactly 2.
        CouplingBetweenObjects().measure(unit.type("Coupled"), ctx()) shouldBe 2.0
    }

    @Test
    fun `coupling resolution is stamped`() {
        // Built with the resolved classifier, so the edges resolve.
        CouplingBetweenObjects().resolution(unit.type("Coupled"), ctx()) shouldBe Resolution.RESOLVED
    }

    @Test
    fun `response-for-class is the methods plus the distinct calls they make`() {
        // 3 own methods + 2 distinct calls (first→Coupled.second, third→Dep2.pong) = exactly 5.
        ResponseForClass().measure(unit.type("Coupled"), ctx()) shouldBe 5.0
    }

    @Test
    fun `coupling self-exclusion uses the simple name in name-based mode`() {
        // With the name-based classifier the refs carry no qualified name, so CBO's self-exclusion falls to
        // its simple-name branch. Coupled still couples to Dep1 and Dep2 (Int excluded, self excluded) → 2.
        val ctx = MeasureContext(nameBasedFixture.classifier(Lang.KOTLIN), nameBasedUnit, null)
        CouplingBetweenObjects().measure(nameBasedUnit.type("Coupled"), ctx) shouldBe 2.0
    }

    @Test
    fun `response-for-class resolution is stamped from the resolved call edges`() {
        ResponseForClass().resolution(unit.type("Coupled"), ctx()) shouldBe Resolution.RESOLVED
    }

    @Test
    fun `lcom4 splits a class with one isolated method into two components`() {
        // first()+second() share `counter` (and first calls second); third() only touches `b`.
        Lcom4().measure(unit.type("Coupled"), ctx()) shouldBe 2.0
    }

    // --- CK suite on Java (the headline use case: CK was defined for Java's class shape) ---

    @Test
    fun `the CK suite measures a Java class with exact values`() {
        // JCoupled (JCoupling.java): methods greet/first/second (no explicit ctor).
        val type = javaUnit.type("JCoupled")
        NumberOfMethods().measure(type, javaCtx()) shouldBe 3.0 // greet, first, second
        WeightedMethodsPerClass().measure(type, javaCtx()) shouldBe 3.0 // each cc 1
        // first→dep.use() + second(): 2 distinct calls → RFC = 3 methods + 2 = 5.
        ResponseForClass().measure(type, javaCtx()) shouldBe 5.0
        // Class type refs: JDep (field) + String (return). The primitive `int` is excluded → exactly 2.
        CouplingBetweenObjects().measure(type, javaCtx()) shouldBe 2.0
        // first+second connected (first calls second); greet touches no field and calls nothing → 2 components.
        Lcom4().measure(type, javaCtx()) shouldBe 2.0
    }

    @Test
    fun `Java coupling and response resolution are RESOLVED when every class edge resolves`() {
        // JDep and String both resolve; the primitive `int` is excluded (not a degraded class edge), so
        // neither the coupling nor the response stamp is dragged down to name-based.
        val type = javaUnit.type("JCoupled")
        CouplingBetweenObjects().resolution(type, javaCtx()) shouldBe Resolution.RESOLVED
        ResponseForClass().resolution(type, javaCtx()) shouldBe Resolution.RESOLVED
    }

    @Test
    fun `an unresolved CLASS type degrades the coupling resolution to name-based`() {
        // The proof that Resolution.weakest is wired through the edges: the `Unresolved` class references
        // GhostType (a type that exists nowhere) → that single name-based edge degrades CBO's whole stamp.
        // (A primitive must NOT do this — that is the bug fixed above; only a real unresolved class does.)
        CouplingBetweenObjects().resolution(unresolvedUnit.type("Unresolved"), ctx()) shouldBe Resolution.NAME_BASED
    }

    // --- Inheritance lenses: driven by a fake ProjectIndex ---

    /** A canned inheritance graph: Leaf -> Mid -> Base, plus one child of Base. */
    private class FakeIndex : ProjectIndex {
        private val supers =
            mapOf(
                "p.Leaf" to listOf("p.Mid"),
                "p.Mid" to listOf("p.Base"),
                "p.Base" to emptyList(),
            )
        private val children = mapOf("p.Base" to 2, "p.Mid" to 1)
        override val internalPackages: Set<String> = setOf("p")

        override fun directSupertypeQNames(typeQName: String): List<String> = supers[typeQName].orEmpty()

        override fun inheritanceResolution(typeQName: String): Resolution = Resolution.RESOLVED

        override fun childrenCountOf(typeQName: String): Int = children[typeQName] ?: 0

        override fun afferentPackagesOf(pkg: String): Set<String> = emptySet()

        override fun efferentPackagesOf(pkg: String): Set<String> = emptySet()
    }

    private fun leafType(qn: String): TypeDecl =
        // A minimal TypeDecl whose qualifiedName drives the index lookups (the node is never walked).
        dev.ktrics.testsupport.typeDecl(name = qn.substringAfterLast('.'), pkg = qn.substringBeforeLast('.'))

    @Test
    fun `depth-of-inheritance-tree walks the resolved supertype chain`() {
        val ctx = MeasureContext(fixture.classifier(unit.lang), unit, FakeIndex())
        DepthOfInheritanceTree().measure(leafType("p.Leaf"), ctx) shouldBe 2.0 // Leaf -> Mid -> Base
        DepthOfInheritanceTree().measure(leafType("p.Base"), ctx) shouldBe 0.0 // root of the in-project chain
    }

    @Test
    fun `number-of-children counts resolved incoming edges`() {
        val ctx = MeasureContext(fixture.classifier(unit.lang), unit, FakeIndex())
        NumberOfChildren().measure(leafType("p.Base"), ctx) shouldBe 2.0
        NumberOfChildren().measure(leafType("p.Leaf"), ctx) shouldBe 0.0
    }

    @Test
    fun `number-of-children is null without an index`() {
        NumberOfChildren().measure(leafType("p.Leaf"), ctx(index = null)).shouldBeNull()
    }

    @Test
    fun `lcom4 is N-A for an interface`() {
        // An interface has no implementation, so cohesion is meaningless → null (not 0, not a number).
        Lcom4().measure(unit.type("Greeter"), ctx()).shouldBeNull()
    }

    @Test
    fun `DIT takes the deepest path through a diamond and terminates on a cycle`() {
        val diamond =
            object : ProjectIndex by FakeIndex() {
                // Top→Direct→Base = depth 2; Top→Indirect→Mid→Base = depth 3 (the deeper path that DIT must
                // report). Loop→Loop is a pathological self-cycle the per-path guard must survive.
                val supers =
                    mapOf(
                        "p.Top" to listOf("p.Direct", "p.Indirect"),
                        "p.Direct" to listOf("p.Base"),
                        "p.Indirect" to listOf("p.Mid"),
                        "p.Mid" to listOf("p.Base"),
                        "p.Base" to emptyList(),
                        "p.Loop" to listOf("p.Loop"),
                    )

                override fun directSupertypeQNames(typeQName: String): List<String> = supers[typeQName].orEmpty()

                override fun inheritanceResolution(typeQName: String): Resolution = Resolution.RESOLVED
            }
        val ctx = MeasureContext(fixture.classifier(unit.lang), unit, diamond)
        // The two paths reconverge on Base; DIT must report the LONGER one (3), not bottom out early at 2.
        DepthOfInheritanceTree().measure(leafType("p.Top"), ctx) shouldBe 3.0
        // A supertype cycle must not infinite-loop; the guard bottoms it out at one hop.
        DepthOfInheritanceTree().measure(leafType("p.Loop"), ctx) shouldBe 1.0
    }

    @Test
    fun `the inheritance lenses stamp the resolution the index reports`() {
        val ctx = MeasureContext(fixture.classifier(unit.lang), unit, FakeIndex())
        DepthOfInheritanceTree().resolution(leafType("p.Leaf"), ctx) shouldBe Resolution.RESOLVED
        NumberOfChildren().resolution(leafType("p.Base"), ctx) shouldBe Resolution.RESOLVED
    }
}
