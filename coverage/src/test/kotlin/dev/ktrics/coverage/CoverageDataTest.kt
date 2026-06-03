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
