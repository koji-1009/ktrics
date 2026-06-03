package dev.ktrics.engine

import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.TypeMetric
import dev.ktrics.metric.clazz.DepthOfInheritanceTree
import dev.ktrics.metric.clazz.Lcom4
import dev.ktrics.metric.clazz.NumberOfChildren
import dev.ktrics.metric.clazz.NumberOfMethods
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
 * Class-level CK calculators over a real session + the real [ProjectIndexImpl], pinning the documented
 * deviations (doc/calibration.md): DIT counts ancestors up to (not including) the root,
 * LCOM4 is N/A on interfaces, and the cohesion graph splits disjoint field clusters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClassMetricTest {
    private lateinit var fixture: SessionFixture
    private lateinit var unit: SourceUnit
    private lateinit var index: ProjectIndexImpl

    @BeforeAll
    fun setUp() {
        val graph = SessionFixture.singleModule(srcRoots = listOf("src/main/kotlin"))
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/hierarchy"), resolved = true)
        unit = fixture.kotlinUnit("Hierarchy.kt")
        index = ProjectIndexImpl(fixture.allUnits())
    }

    @AfterAll
    fun tearDown() = fixture.close()

    private fun ctx() = MeasureContext(fixture.classifier(unit.lang), unit, index)

    private fun measure(
        metric: TypeMetric,
        type: TypeDecl,
    ) = metric.measure(type, ctx())

    @Test
    fun `DIT counts the resolved supertype chain up to but not including the root`() {
        val dit = DepthOfInheritanceTree()
        // Dog -> Pet -> Animal -> Speaker; Any/Object is the implicit root and is not counted.
        measure(dit, unit.type("Dog")) shouldBe 3.0
        measure(dit, unit.type("Animal")) shouldBe 1.0
        // An interface with no project-local supertype sits at depth 0.
        measure(dit, unit.type("Speaker")) shouldBe 0.0
    }

    @Test
    fun `DIT takes the longest path through a diamond, not undercounting via a shared visited set`() {
        // Diamond -> {Left, Right} -> Top. Both paths are length 2; a single shared `seen` set would let
        // the first branch mark Top visited and make the second bottom out, undercounting. Expect 2.
        measure(DepthOfInheritanceTree(), unit.type("Diamond")) shouldBe 2.0
    }

    @Test
    fun `NOC counts direct resolved children`() {
        val noc = NumberOfChildren()
        measure(noc, unit.type("Pet")) shouldBe 1.0 // Dog
        measure(noc, unit.type("Speaker")) shouldBe 1.0 // Animal
        measure(noc, unit.type("Dog")) shouldBe 0.0 // a leaf
    }

    @Test
    fun `LCOM4 is N-slash-A on an interface`() {
        measure(Lcom4(), unit.type("Speaker")).shouldBeNull()
    }

    @Test
    fun `LCOM4 is one for a cohesive class`() {
        measure(Lcom4(), unit.type("Cohesive")) shouldBe 1.0
    }

    @Test
    fun `LCOM4 splits two disjoint field clusters into two components`() {
        measure(Lcom4(), unit.type("TwoClusters")) shouldBe 2.0
    }

    @Test
    fun `NOM counts declared methods, excluding constructors`() {
        measure(NumberOfMethods(), unit.type("TwoClusters")) shouldBe 4.0
        measure(NumberOfMethods(), unit.type("Dog")) shouldBe 1.0
    }
}
