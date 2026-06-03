package dev.ktrics.report

import dev.ktrics.ir.CallGraphSignal
import dev.ktrics.ir.Lang
import dev.ktrics.ir.Span
import dev.ktrics.ir.UnusedEntry
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.DismissalState
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Severity
import dev.ktrics.metric.StableId
import dev.ktrics.metric.Violation
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** `--limit` caps each block after the sort and tallies the drops under `truncated:`. */
class AiReporterLimitTest {
    private fun violation(
        metric: String,
        value: Double,
        threshold: Double = 10.0,
    ): Violation {
        val span = Span("src/Foo.kt", 10, 1, 10, 1, 0, 0)
        return Violation(
            id = StableId.of("src/Foo.kt", "com.x.$metric", metric),
            metricId = metric, severity = Severity.WARNING, polarity = Polarity.LOWER_IS_BETTER, appliesTo = AppliesTo.BOTH,
            file = "src/Foo.kt", scope = "com.x.$metric", scopeName = "Foo.$metric", scopeKind = ScopeKind.FUNCTION,
            lang = Lang.KOTLIN, value = value, threshold = threshold, span = span,
            rationale = "r", refactorHints = emptyList(), references = emptyList(),
        )
    }

    private fun report(
        violations: List<Violation> = emptyList(),
        unused: List<UnusedEntry> = emptyList(),
        signals: List<CallGraphSignal> = emptyList(),
    ): AnalysisReport {
        val files = listOf(FileEntry("src/Foo.kt", Lang.KOTLIN, violations.size))
        return AnalysisReport(
            version = "t",
            root = "/p",
            summary = ReportSummary.of(violations, files),
            violations = violations,
            files = files,
            unused = unused,
            signals = signals,
        )
    }

    @Test
    fun `limit keeps the top-N violations and tallies the rest under truncated`() {
        val violations = (1..5).map { violation("m$it", value = it * 10.0) }
        val text = AiReporter(limit = 2).render(report(violations = violations))
        // 5 violations, limit 2 → 3 dropped.
        text shouldContain "truncated:"
        text shouldContain "  violations: 3"
    }

    @Test
    fun `the kept violations are the highest breach ratios`() {
        val violations = listOf(violation("small", 11.0), violation("huge", 100.0))
        val text = AiReporter(limit = 1).render(report(violations = violations))
        // huge (10x) outranks small (1.1x) and is the one kept.
        text shouldContain "metric: huge"
        text shouldNotContain "metric: small"
    }

    @Test
    fun `no truncated block appears when everything fits under the limit`() {
        val text = AiReporter(limit = 10).render(report(violations = listOf(violation("m1", 20.0))))
        text shouldNotContain "truncated:"
    }

    @Test
    fun `limit independently caps unused and signals and tallies each`() {
        val unused = (1..3).map { UnusedEntry("src/Foo.kt", it, "function", "dead$it") }
        val signals = (1..4).map { CallGraphSignal("src/Foo.kt", "s$it", "method", it, it, it, 0, 0) }
        val text = AiReporter(limit = 1).render(report(unused = unused, signals = signals))
        text shouldContain "  unused: 2" // 3 - 1
        text shouldContain "  signals: 3" // 4 - 1
    }

    @Test
    fun `a null limit keeps everything and emits no truncated block`() {
        val violations = (1..3).map { violation("m$it", value = it * 10.0) }
        val text = AiReporter(limit = null).render(report(violations = violations))
        text shouldNotContain "truncated:"
        text shouldContain "metric: m1"
        text shouldContain "metric: m3"
    }

    @Test
    fun `a rejected dismissal surfaces as dismissalRejected and keeps the violation`() {
        val v =
            violation("cyclomatic-complexity", 30.0).copy(
                dismissal = DismissalState.Rejected(reason = "wip", minReasonLength = 12),
            )
        val text = AiReporter().render(report(violations = listOf(v)))
        text shouldContain "dismissalRejected:"
        text shouldContain "reason too short"
    }

    @Test
    fun `an empty violations set renders the explicit empty marker`() {
        AiReporter().render(report()) shouldContain "violations: []"
    }
}
