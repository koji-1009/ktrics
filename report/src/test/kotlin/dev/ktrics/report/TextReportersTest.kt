package dev.ktrics.report

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.Span
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Severity
import dev.ktrics.metric.StableId
import dev.ktrics.metric.Violation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** Console / Markdown / catalogue rendering contracts. */
class TextReportersTest {
    private fun violation(
        metric: String,
        severity: Severity,
        value: Double,
        threshold: Double,
        line: Int = 12,
    ): Violation {
        val span = Span("src/Foo.kt", line, 3, line, 10, 0, 0)
        return Violation(
            id = StableId.of("src/Foo.kt", "com.x.Foo.$metric", metric),
            metricId = metric, severity = severity, polarity = Polarity.LOWER_IS_BETTER, appliesTo = AppliesTo.BOTH,
            file = "src/Foo.kt", scope = "com.x.Foo.$metric", scopeName = "Foo.$metric", scopeKind = ScopeKind.FUNCTION,
            lang = Lang.KOTLIN, value = value, threshold = threshold, span = span,
            rationale = "why", refactorHints = listOf("hint"), references = listOf("ref"),
        )
    }

    private fun report(vararg v: Violation): AnalysisReport {
        val files = listOf(FileEntry("src/Foo.kt", Lang.KOTLIN, v.size))
        return AnalysisReport(
            version = "1.2.3",
            root = "/proj",
            summary = ReportSummary.of(v.toList(), files),
            violations = v.toList(),
            files = files,
        )
    }

    @Test
    fun `console summarizes a clean run`() {
        val text = ConsoleReporter().render(report())
        text shouldContain "✓ no violations"
        text shouldContain "java 0, kotlin 1"
    }

    @Test
    fun `console renders one line per violation with severity tag and location`() {
        val text = ConsoleReporter().render(report(violation("cyclomatic-complexity", Severity.ERROR, 30.0, 20.0)))
        text shouldContain "error"
        text shouldContain "12:3"
        text shouldContain "cyclomatic-complexity"
        text shouldContain "30 > 20"
        text shouldContain "1 violation(s): 1 error(s), 0 warning(s)"
    }

    @Test
    fun `markdown emits the summary table and a violations row`() {
        val text = MarkdownReporter().render(report(violation("lcom4", Severity.WARNING, 3.0, 2.0)))
        text shouldStartWith "# ktrics report"
        text shouldContain "| files | java | kotlin | violations | errors | warnings |"
        text shouldContain "`lcom4`"
        text shouldContain "## Violations"
    }

    @Test
    fun `markdown shows the no-violations marker when clean`() {
        MarkdownReporter().render(report()) shouldContain "✓ No violations."
    }

    @Test
    fun `markdown renders a full row in column order with an em-dash for a null resolution`() {
        // Pin the WHOLE row (column order + integer formatNumber + the `—` null-resolution placeholder),
        // not just individual cells — a column reorder or a dropped cell must fail this.
        val v = violation("lcom4", Severity.WARNING, 3.0, 2.0) // resolution defaults to null
        val text = MarkdownReporter().render(report(v))
        text shouldContain "| warning | `lcom4` | `Foo.lcom4` | 3 | 2 | `src/Foo.kt:12` | kotlin | — | `${v.id}` |"
    }

    @Test
    fun `console groups violations by file with the per-file actionability order`() {
        val aError = violation("cyclomatic-complexity", Severity.ERROR, 30.0, 20.0).copy(file = "src/A.kt", scope = "A.f")
        val aWarn = violation("lcom4", Severity.WARNING, 3.0, 2.0).copy(file = "src/A.kt", scope = "A.g")
        val bError = violation("npath-complexity", Severity.ERROR, 250.0, 200.0).copy(file = "src/B.kt", scope = "B.h")
        val files = listOf(FileEntry("src/A.kt", Lang.KOTLIN, 2), FileEntry("src/B.kt", Lang.KOTLIN, 1))
        val vs = listOf(aError, aWarn, bError)
        val multi =
            AnalysisReport(version = "1", root = "/p", summary = ReportSummary.of(vs, files), violations = vs, files = files)
        val lines = ConsoleReporter().render(multi).lines()

        val aHeader = lines.indexOf("src/A.kt")
        val bHeader = lines.indexOf("src/B.kt")
        (aHeader >= 0 && bHeader >= 0) shouldBe true
        (aHeader < bHeader) shouldBe true // files are grouped and sorted (A before B)
        // Within src/A.kt the error outranks the warning (actionability order inside the group).
        val aBlock = lines.subList(aHeader, bHeader)
        (aBlock.indexOfFirst { it.contains("cyclomatic-complexity") } < aBlock.indexOfFirst { it.contains("lcom4") }) shouldBe true
    }

    @Test
    fun `markdown escapes a pipe in the scope name`() {
        val v = violation("cyclomatic-complexity", Severity.WARNING, 11.0, 10.0).copy(scopeName = "f(a|b)")
        MarkdownReporter().render(report(v)) shouldContain "f(a\\|b)"
    }

    @Test
    fun `markdown renders unused, signals, and stale-dismissal sections when present`() {
        val base = report()
        val full =
            base.copy(
                unused = listOf(dev.ktrics.ir.UnusedEntry("src/Foo.kt", 4, "function", "dead")),
                signals = listOf(dev.ktrics.ir.CallGraphSignal("src/Foo.kt", "pkg.Foo.hot", "method", 9, 5, 8, 2, 3)),
                staleDismissals =
                    listOf(
                        dev.ktrics.ir.StaleDismissal(source = "comment", file = "src/Foo.kt", line = 3, metric = "lcom4", reason = "old"),
                    ),
            )
        val text = MarkdownReporter().render(full)
        text shouldContain "## Unused (reference)"
        text shouldContain "| function | `dead` | `src/Foo.kt:4` |"
        text shouldContain "## Signals (reference)"
        text shouldContain "| `pkg.Foo.hot` | method | 5 | 8 | 2 | 3 | `src/Foo.kt:9` |"
        text shouldContain "## Stale Dismissals"
        text shouldContain "| comment | `src/Foo.kt:3` | `lcom4` | old |"
    }

    @Test
    fun `markdown omits the reference sections on a clean report`() {
        val text = MarkdownReporter().render(report())
        text.contains("## Unused") shouldBe false
        text.contains("## Signals") shouldBe false
        text.contains("## Stale Dismissals") shouldBe false
    }

    @Test
    fun `catalogue rules lists every metric with the count header`() {
        val rules = CatalogRenderer.rules()
        rules shouldContain "${BuiltinMetrics.all.size} metrics"
        rules shouldContain "cyclomatic-complexity"
        rules shouldContain "== function level =="
        // Off-by-default metrics are annotated so they are never assumed active.
        rules shouldContain "(off by default)"
    }

    @Test
    fun `explainMetric renders a known metric and returns null for an unknown one`() {
        val explain = CatalogRenderer.explainMetric("cyclomatic-complexity")!!
        explain shouldContain "metric: cyclomatic-complexity"
        explain shouldContain "rationale:"
        CatalogRenderer.explainMetric("not-a-metric") shouldBe null
    }

    @Test
    fun `console formats decimal values, the warning tag, and the name-based note`() {
        val v =
            violation("coupling-between-objects", Severity.WARNING, 12.5, 10.0)
                .copy(resolution = Resolution.NAME_BASED)
        val text = ConsoleReporter().render(report(v))
        text shouldContain "warn" // the warning tag (vs the error tag)
        text shouldContain "12.50" // the decimal branch of formatNumber
        text shouldContain "[name-based]" // per-violation resolution stamp
        text shouldContain "name-based (classpath incomplete)" // the summary note when any result is name-based
    }

    @Test
    fun `markdown renders the resolution column and decimal values`() {
        val v =
            violation("coupling-between-objects", Severity.WARNING, 12.5, 10.0)
                .copy(resolution = Resolution.RESOLVED)
        val text = MarkdownReporter().render(report(v))
        text shouldContain "| resolved |"
        text shouldContain "12.50"
    }
}
