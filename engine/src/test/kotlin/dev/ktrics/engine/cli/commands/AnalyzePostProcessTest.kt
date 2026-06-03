package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandSink
import dev.ktrics.ir.CallGraphSignal
import dev.ktrics.ir.Lang
import dev.ktrics.ir.Span
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.MetricResult
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Severity
import dev.ktrics.metric.StableId
import dev.ktrics.metric.Violation
import dev.ktrics.report.AnalysisReport
import dev.ktrics.report.FileEntry
import dev.ktrics.report.ReportSummary
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** postProcess shaping: signals follow kept scopes, measurements are stripped. */
class AnalyzePostProcessTest {
    private object NoSink : CommandSink {
        override fun out(text: String) {}

        override fun err(text: String) {}
    }

    private fun ctx(vararg args: String) = CommandContext(args.toList(), File("."), emptyMap(), NoSink)

    private fun violation(scope: String): Violation =
        Violation(
            id = StableId.of("src/Foo.kt", scope, "cyclomatic-complexity"),
            metricId = "cyclomatic-complexity", severity = Severity.ERROR, polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH, file = "src/Foo.kt", scope = scope, scopeName = scope,
            scopeKind = ScopeKind.FUNCTION, lang = Lang.KOTLIN, value = 30.0, threshold = 20.0,
            span = Span("src/Foo.kt", 1, 1, 1, 1, 0, 0),
            rationale = "r", refactorHints = emptyList(), references = emptyList(),
        )

    private fun signal(scopeName: String) = CallGraphSignal("src/Foo.kt", scopeName, "method", 1, 0, 0, 0, 0)

    private fun report(): AnalysisReport {
        val violations = listOf(violation("com.x.A.foo"))
        val files = listOf(FileEntry("src/Foo.kt", Lang.KOTLIN, 1))
        return AnalysisReport(
            version = "t",
            root = "/p",
            summary = ReportSummary.of(violations, files),
            violations = violations,
            files = files,
            measurements =
                listOf(
                    MetricResult(
                        "cyclomatic-complexity", "src/Foo.kt", "com.x.A.foo", "com.x.A.foo", ScopeKind.FUNCTION, Lang.KOTLIN, 30.0, null,
                        Span(
                            "src/Foo.kt",
                            1,
                            1,
                            1,
                            1,
                            0,
                            0,
                        ),
                    ),
                ),
            signals = listOf(signal("com.x.A.foo"), signal("com.x.B.bar")),
        )
    }

    @Test
    fun `signals are filtered down to the scopes that still have a violation`() {
        val out = runPostProcess(ctx(), report())!!
        // com.x.A.foo has a violation (kept); com.x.B.bar does not (dropped).
        out.signals.map { it.scopeName } shouldContainExactly listOf("com.x.A.foo")
    }

    @Test
    fun `measurements are stripped from the rendered report`() {
        runPostProcess(ctx(), report())!!.measurements shouldBe emptyList()
    }

    @Test
    fun `the summary is recomputed from the kept violations`() {
        val out = runPostProcess(ctx(), report())!!
        out.summary.violations shouldBe 1
        out.summary.errors shouldBe 1
    }

    /** Bridges to the internal postProcess (same module, internal visibility). */
    private fun runPostProcess(
        ctx: CommandContext,
        report: AnalysisReport,
    ) = AnalyzeCommand.postProcess(ctx, report)
}
