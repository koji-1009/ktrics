package dev.ktrics.coverage

import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** MethodCoverage ratio math + overload aggregation in the parser. */
class CoverageDataTest {
    @Test
    fun `branch ratio is preferred over line ratio for the effective ratio`() {
        val cov = MethodCoverage(branchCovered = 3, branchMissed = 1, lineCovered = 10, lineMissed = 0)
        cov.branchRatio!! shouldBeExactly 0.75
        cov.lineRatio!! shouldBeExactly 1.0
        cov.effectiveRatio!! shouldBeExactly 0.75 // branch wins
    }

    @Test
    fun `with no branches the effective ratio falls back to lines`() {
        val cov = MethodCoverage(branchCovered = 0, branchMissed = 0, lineCovered = 3, lineMissed = 1)
        cov.branchRatio.shouldBeNull()
        cov.effectiveRatio!! shouldBeExactly 0.75
    }

    @Test
    fun `a method with no counters at all has no ratio`() {
        val cov = MethodCoverage(0, 0, 0, 0)
        cov.branchRatio.shouldBeNull()
        cov.lineRatio.shouldBeNull()
        cov.effectiveRatio.shouldBeNull()
    }

    @Test
    fun `plus sums every counter`() {
        val a = MethodCoverage(1, 2, 3, 4)
        val b = MethodCoverage(10, 20, 30, 40)
        (a + b) shouldBe MethodCoverage(11, 22, 33, 44)
    }

    @Test
    fun `merge unions per-module reports and sums shared keys`() {
        // Multi-module builds emit one report per module; merge must union the scopes and aggregate
        // a scope present in both (e.g. a shared source set tested from two modules).
        val a = JacocoParser.parse(report("pkg/OnlyA" to (2 to 0), "pkg/Shared" to (1 to 3)))
        val b = JacocoParser.parse(report("pkg/OnlyB" to (4 to 0), "pkg/Shared" to (3 to 1)))
        val merged = a.merge(b)
        merged.complexityJustified("pkg.OnlyA.m") shouldBe true
        merged.complexityJustified("pkg.OnlyB.m") shouldBe true
        // Shared: covered 1+3=4, missed 3+1=4 → 0.5 < 0.8.
        merged.complexityJustified("pkg.Shared.m") shouldBe false
        merged.forScope("pkg.Shared.m") shouldBe MethodCoverage(4, 4, 0, 0)
    }

    @Test
    fun `merge with an empty side returns the other side unchanged`() {
        val a = JacocoParser.parse(report("pkg/A" to (1 to 0)))
        a.merge(CoverageData.EMPTY).forScope("pkg.A.m") shouldBe MethodCoverage(1, 0, 0, 0)
        CoverageData.EMPTY.merge(a).forScope("pkg.A.m") shouldBe MethodCoverage(1, 0, 0, 0)
    }

    /** A minimal JaCoCo report with one `m` method per class: (covered to missed) branch counters. */
    private fun report(vararg classes: Pair<String, Pair<Int, Int>>): String =
        buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><report name="r"><package name="pkg">""")
            classes.forEach { (name, counters) ->
                append("""<class name="$name" sourcefilename="X.kt"><method name="m" desc="()V" line="1">""")
                append("""<counter type="BRANCH" missed="${counters.second}" covered="${counters.first}"/></method></class>""")
            }
            append("</package></report>")
        }

    @Test
    fun `overloaded methods sharing a scope key aggregate their counters`() {
        // JaCoCo emits one <method> per overload (distinct desc); the IR scope key `Owner.method` is
        // unsignatured, so both must roll up into one MethodCoverage rather than the last overwriting.
        val xml =
            """
            <report name="t">
              <package name="com/example">
                <class name="com/example/Svc" sourcefilename="Svc.java">
                  <method name="process" desc="(I)V" line="5">
                    <counter type="BRANCH" missed="2" covered="2"/>
                    <counter type="LINE" missed="1" covered="1"/>
                  </method>
                  <method name="process" desc="(Ljava/lang/String;)V" line="9">
                    <counter type="BRANCH" missed="0" covered="6"/>
                    <counter type="LINE" missed="0" covered="3"/>
                  </method>
                </class>
              </package>
            </report>
            """.trimIndent()
        val data = JacocoParser.parse(xml)
        val cov = data.forScope("com.example.Svc.process")!!
        // BRANCH: covered 2+6=8, missed 2+0=2 → 8/10 = 0.8 (exactly the justified threshold).
        cov.branchRatio!! shouldBeExactly 0.8
        data.complexityJustified("com.example.Svc.process") shouldBe true
    }

    @Test
    fun `complexityJustified is false just below the 0_8 threshold`() {
        // The aggregation test pins exactly 0.8 → justified; this brackets the other side at 0.79 → not.
        val xml =
            """
            <report name="t"><package name="p"><class name="p/C" sourcefilename="C.kt">
            <method name="m" desc="()V"><counter type="BRANCH" missed="21" covered="79"/></method>
            </class></package></report>
            """.trimIndent()
        JacocoParser.parse(xml).complexityJustified("p.C.m") shouldBe false // 79/100 = 0.79 < 0.8
    }

    @Test
    fun `a missing report file yields empty coverage`() {
        JacocoParser.parse(java.io.File("does-not-exist.xml")).isEmpty() shouldBe true
        CoverageData.EMPTY.isEmpty() shouldBe true
    }
}
