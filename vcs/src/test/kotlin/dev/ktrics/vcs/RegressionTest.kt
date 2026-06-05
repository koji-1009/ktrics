package dev.ktrics.vcs

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Span
import dev.ktrics.metric.MetricResult
import dev.ktrics.metric.ScopeKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RegressionTest {
    private fun result(
        metric: String,
        scope: String,
        value: Double,
    ): MetricResult =
        MetricResult(metric, "Foo.kt", scope, scope, ScopeKind.FUNCTION, Lang.KOTLIN, value, null, Span("Foo.kt", 1, 1, 1, 1, 0, 0))

    @Test
    fun `classifies by polarity`() {
        val before = listOf(result("cyclomatic-complexity", "a", 10.0), result("cyclomatic-complexity", "b", 5.0))
        val after = listOf(result("cyclomatic-complexity", "a", 6.0), result("cyclomatic-complexity", "c", 3.0))
        val report = Regression.compare(before, after)

        // a: 10 → 6 improved (lower is better); b removed; c added.
        report.improved shouldBe 1
        report.removed shouldBe 1
        report.added shouldBe 1
    }

    @Test
    fun `delta is after minus before, or null when one side is missing`() {
        val before = listOf(result("cyclomatic-complexity", "a", 10.0), result("cyclomatic-complexity", "b", 5.0))
        val after = listOf(result("cyclomatic-complexity", "a", 6.0), result("cyclomatic-complexity", "c", 3.0))
        val byScope = Regression.compare(before, after).entries.associateBy { it.scope }
        byScope.getValue("a").delta shouldBe -4.0 // 6 - 10, both sides present
        byScope.getValue("b").delta shouldBe null // removed → after is null
        byScope.getValue("c").delta shouldBe null // added → before is null
    }

    @Test
    fun `classifies a lower-is-better growth as regressed`() {
        val before = listOf(result("cyclomatic-complexity", "a", 6.0))
        val after = listOf(result("cyclomatic-complexity", "a", 11.0))
        val report = Regression.compare(before, after)
        report.regressed shouldBe 1
        report.improved shouldBe 0
        report.entries.single().change shouldBe Change.REGRESSED
    }

    @Test
    fun `higher-is-better improves when the value rises`() {
        val before = listOf(result("maintainability-index", "a", 50.0))
        val after = listOf(result("maintainability-index", "a", 70.0))
        Regression.compare(before, after).entries.single().change shouldBe Change.IMPROVED
    }

    @Test
    fun `an informational metric that moved is NEUTRAL_DELTA, never regressed or improved`() {
        // The movement is a real signal (dartrics' neutralDelta) — it just carries no verdict.
        val before = listOf(result("types-per-file", "F", 2.0))
        val after = listOf(result("types-per-file", "F", 9.0))
        val report = Regression.compare(before, after)
        report.entries.single().change shouldBe Change.NEUTRAL_DELTA
        report.neutralDelta shouldBe 1
        report.regressed shouldBe 0
        report.improved shouldBe 0
        report.unchanged shouldBe 0
    }

    @Test
    fun `an informational metric that did not move stays UNCHANGED`() {
        val before = listOf(result("types-per-file", "F", 2.0))
        val after = listOf(result("types-per-file", "F", 2.0))
        val report = Regression.compare(before, after)
        report.entries.single().change shouldBe Change.UNCHANGED
        report.neutralDelta shouldBe 0
    }

    @Test
    fun `an added or removed informational metric is ADDED or REMOVED, not UNCHANGED`() {
        // The add/remove checks precede the informational→UNCHANGED rule, so presence still wins.
        val before = listOf(result("types-per-file", "gone", 2.0))
        val after = listOf(result("types-per-file", "fresh", 3.0))
        val byScope = Regression.compare(before, after).entries.associateBy { it.scope }
        byScope.getValue("gone").change shouldBe Change.REMOVED
        byScope.getValue("fresh").change shouldBe Change.ADDED
    }

    @Test
    fun `entries are returned in a stable sorted order`() {
        val before = listOf(result("cyclomatic-complexity", "zebra", 1.0), result("cyclomatic-complexity", "alpha", 1.0))
        Regression.compare(before, before).entries.map { it.scope } shouldBe listOf("alpha", "zebra") // sorted by scope
    }

    @Test
    fun `an unchanged value is classified unchanged`() {
        val before = listOf(result("cyclomatic-complexity", "a", 7.0))
        val after = listOf(result("cyclomatic-complexity", "a", 7.0))
        Regression.compare(before, after).unchanged shouldBe 1
    }

    @Test
    fun `detects a cosmetic refactor`() {
        // 4 tiny helpers added (cc 1), sloc up by 40 (> 4*4), cc reduction 1 (< 2*4).
        val before = listOf(result("cyclomatic-complexity", "big", 12.0), result("source-lines-of-code", "big", 50.0))
        val after =
            buildList {
                add(result("cyclomatic-complexity", "big", 11.0))
                add(result("source-lines-of-code", "big", 90.0))
                repeat(4) { i ->
                    add(result("cyclomatic-complexity", "helper$i", 1.0))
                    add(result("source-lines-of-code", "helper$i", 3.0))
                }
            }
        Regression.compare(before, after).cosmeticSplitDetected shouldBe true
    }

    @Test
    fun `genuine simplification is not flagged cosmetic`() {
        val before = listOf(result("cyclomatic-complexity", "big", 20.0), result("source-lines-of-code", "big", 80.0))
        val after = listOf(result("cyclomatic-complexity", "big", 8.0), result("source-lines-of-code", "big", 40.0))
        Regression.compare(before, after).cosmeticSplitDetected shouldBe false
    }

    // --- cosmetic-refactor heuristic boundaries: tinyHelpers >= 3 AND slocDelta > 4*h AND ccReduction < 2*h ---

    @Test
    fun `fewer than three tiny helpers is not a cosmetic refactor`() {
        // Two tiny helpers (cc 1) is below MIN_HELPERS=3 even though sloc balloons and cc barely moves.
        val before = listOf(result("cyclomatic-complexity", "big", 12.0), result("source-lines-of-code", "big", 50.0))
        val after =
            buildList {
                add(result("cyclomatic-complexity", "big", 11.0))
                add(result("source-lines-of-code", "big", 90.0))
                repeat(2) { i ->
                    add(result("cyclomatic-complexity", "h$i", 1.0))
                    add(result("source-lines-of-code", "h$i", 3.0))
                }
            }
        Regression.compare(before, after).cosmeticSplitDetected shouldBe false
    }

    @Test
    fun `a new scope above the tiny-complexity cutoff does not count as a helper`() {
        // h0/h1 are tiny (cc 1) but h2 has cc 3 (> TINY_CC=2) → only 2 helpers counted → below MIN_HELPERS.
        val before = listOf(result("cyclomatic-complexity", "big", 12.0), result("source-lines-of-code", "big", 50.0))
        val after =
            buildList {
                add(result("cyclomatic-complexity", "big", 11.0))
                add(result("source-lines-of-code", "big", 200.0))
                add(result("cyclomatic-complexity", "h0", 1.0))
                add(result("cyclomatic-complexity", "h1", 1.0))
                add(result("cyclomatic-complexity", "h2", 3.0))
            }
        Regression.compare(before, after).cosmeticSplitDetected shouldBe false
    }

    @Test
    fun `sloc growth exactly at the 4x boundary is not flagged`() {
        // 3 tiny helpers; slocDelta = (10 + 3*4) - 10 = 12 == 4*3, and `slocDelta > 4*h` is strict → false.
        val before = listOf(result("cyclomatic-complexity", "big", 5.0), result("source-lines-of-code", "big", 10.0))
        val after =
            buildList {
                add(result("cyclomatic-complexity", "big", 5.0)) // cc unchanged → ccReduction 0
                add(result("source-lines-of-code", "big", 10.0))
                repeat(3) { i ->
                    add(result("cyclomatic-complexity", "h$i", 1.0))
                    add(result("source-lines-of-code", "h$i", 4.0))
                }
            }
        Regression.compare(before, after).cosmeticSplitDetected shouldBe false
    }

    @Test
    fun `a real complexity reduction at the 2x boundary suppresses the cosmetic flag`() {
        // 3 tiny helpers and sloc balloons, BUT ccReduction = 10-4 = 6 == 2*3 and `ccReduction < 2*h` is
        // strict → the genuine-simplification guard fires and the refactor is NOT called cosmetic.
        val before = listOf(result("cyclomatic-complexity", "big", 10.0), result("source-lines-of-code", "big", 10.0))
        val after =
            buildList {
                add(result("cyclomatic-complexity", "big", 4.0))
                add(result("source-lines-of-code", "big", 60.0))
                repeat(3) { i ->
                    add(result("cyclomatic-complexity", "h$i", 1.0))
                    add(result("source-lines-of-code", "h$i", 4.0))
                }
            }
        Regression.compare(before, after).cosmeticSplitDetected shouldBe false
    }
}
