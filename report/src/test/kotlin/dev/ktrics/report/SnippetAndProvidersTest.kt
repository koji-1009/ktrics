package dev.ktrics.report

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Span
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Severity
import dev.ktrics.metric.StableId
import dev.ktrics.metric.Violation
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Snippet slicing, source providers, reporter factory, and per-metric explain. */
class SnippetAndProvidersTest {
    private fun violation(
        file: String,
        line: Int,
    ): Violation {
        val span = Span(file, line, 1, line, 1, 0, 0)
        return Violation(
            id = StableId.of(file, "com.x.f", "cyclomatic-complexity"),
            metricId = "cyclomatic-complexity", severity = Severity.ERROR, polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH, file = file, scope = "com.x.f", scopeName = "f()",
            scopeKind = ScopeKind.FUNCTION, lang = Lang.KOTLIN, value = 30.0, threshold = 20.0, span = span,
            rationale = "r", refactorHints = emptyList(), references = emptyList(),
        )
    }

    private fun report(v: Violation) =
        AnalysisReport(
            version = "t",
            root = "/p",
            summary = ReportSummary.of(listOf(v), listOf(FileEntry(v.file, Lang.KOTLIN, 1))),
            violations = listOf(v),
            files = listOf(FileEntry(v.file, Lang.KOTLIN, 1)),
        )

    @Test
    fun `the filesystem provider slices a line window and clamps the bounds`() {
        val dir = createTempDirectory("src").toFile()
        try {
            File(dir, "Foo.kt").writeText((1..10).joinToString("\n") { "line$it" })
            val sp = FileSystemSourceProvider(dir)
            val (first, lines) = sp.lines("Foo.kt", 4, 6)
            first shouldBe 4
            lines shouldContainExactly listOf("line4", "line5", "line6")
            // Out-of-range bounds clamp to the file.
            sp.lines("Foo.kt", -2, 100).second.size shouldBe 10
            // A missing file yields no snippet.
            sp.lines("Ghost.kt", 1, 3).second shouldBe emptyList()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the none provider never returns lines`() {
        SourceProvider.None.lines("x", 1, 5).second shouldBe emptyList()
    }

    @Test
    fun `the ai snippet is a line-plus-or-minus-3 window marked only on the violation line`() {
        val dir = createTempDirectory("src").toFile()
        try {
            File(dir, "Foo.kt").writeText((1..12).joinToString("\n") { "code$it" })
            val text = AiReporter(FileSystemSourceProvider(dir)).render(report(violation("Foo.kt", 6)))
            text shouldContain "snippet: |"
            // window = line ± SNIPPET_RADIUS(3) → lines 3..9 inclusive; 2 and 10 are outside it.
            text shouldContain "code3"
            text shouldContain "code9"
            text.contains("code2") shouldBe false
            text.contains("code10") shouldBe false
            // exactly one line carries the `>` marker, and it is the violation's own line 6.
            val snippetLines = text.lines().filter { it.contains("| code") }
            snippetLines.count { it.trimStart().startsWith(">") } shouldBe 1
            snippetLines.first { it.trimStart().startsWith(">") } shouldContain "6| code6"
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the ai reporter escapes backslash, quote, newline and control chars in quoted scalars`() {
        val span = Span("F.kt", 1, 1, 1, 1, 0, 0)
        val v =
            Violation(
                id = StableId.of("F.kt", "com.x.f", "cyclomatic-complexity"),
                metricId = "cyclomatic-complexity", severity = Severity.ERROR, polarity = Polarity.LOWER_IS_BETTER,
                appliesTo = AppliesTo.BOTH, file = "F.kt", scope = "com.x.f", scopeName = "f()",
                scopeKind = ScopeKind.FUNCTION, lang = Lang.KOTLIN, value = 30.0, threshold = 20.0, span = span,
                // backslash, quote, newline, CR, tab, and a raw control char — each must be escaped so the
                // quoted scalar stays valid single-line YAML (a raw newline would break a consuming parser).
                rationale = "a\\b\"c\nd\re\tfg", refactorHints = emptyList(), references = emptyList(),
            )
        val line = AiReporter().render(report(v)).lines().first { it.trim().startsWith("rationale:") }
        line shouldContain "\\\\" // backslash escaped
        line shouldContain "\\\"" // quote escaped
        line shouldContain "\\n" // newline escaped (stays on one line)
        line shouldContain "\\r" // carriage return escaped
        line shouldContain "\\t" // tab escaped
    }

    @Test
    fun `the ai reporter hex-escapes a raw control character`() {
        val span = Span("F.kt", 1, 1, 1, 1, 0, 0)
        val v =
            Violation(
                id = StableId.of("F.kt", "com.x.f", "cyclomatic-complexity"),
                metricId = "cyclomatic-complexity", severity = Severity.ERROR, polarity = Polarity.LOWER_IS_BETTER,
                appliesTo = AppliesTo.BOTH, file = "F.kt", scope = "com.x.f", scopeName = "f()",
                scopeKind = ScopeKind.FUNCTION, lang = Lang.KOTLIN, value = 30.0, threshold = 20.0, span = span,
                // a raw U+0001 control char (built without a string escape) must be hex-escaped, or it would
                // break a YAML consumer — this is the `c < ' '` branch the other escaping test does not reach.
                rationale = "x" + 1.toChar() + "y", refactorHints = emptyList(), references = emptyList(),
            )
        val line = AiReporter().render(report(v)).lines().first { it.trim().startsWith("rationale:") }
        line shouldContain "\\x01"
    }

    @Test
    fun `the ai violation block emits its fields in the contractual order`() {
        val text = AiReporter().render(report(violation("F.kt", 5)))
        val order =
            listOf(
                "  - id:", "    metric:", "    severity:", "    language:",
                "    polarity:", "    scope:", "    location:", "    value:", "    threshold:", "    rationale:",
            )
        // Each field appears, and strictly before the next — a reorder or a dropped field fails this.
        order.zipWithNext().forEach { (a, b) ->
            val ia = text.indexOf(a)
            val ib = text.indexOf(b)
            (ia in 0 until ib) shouldBe true
        }
    }

    @Test
    fun `the reporter factory returns the reporter for each format`() {
        Reporters.forFormat(ReporterFormat.CONSOLE).shouldBeInstanceOf<ConsoleReporter>()
        Reporters.forFormat(ReporterFormat.JSON).shouldBeInstanceOf<JsonReporter>()
        Reporters.forFormat(ReporterFormat.MD).shouldBeInstanceOf<MarkdownReporter>()
        Reporters.forFormat(ReporterFormat.AI).shouldBeInstanceOf<AiReporter>()
        Reporters.forFormat(ReporterFormat.SARIF).shouldBeInstanceOf<SarifReporter>()
    }

    @Test
    fun `reporter format ids resolve case-insensitively and reject unknowns`() {
        ReporterFormat.fromId("AI") shouldBe ReporterFormat.AI
        ReporterFormat.fromId("console") shouldBe ReporterFormat.CONSOLE
        ReporterFormat.fromId("xml").shouldBeNull()
        ReporterFormat.ids shouldContainExactly listOf("console", "json", "md", "ai", "sarif")
    }

    @Test
    fun `explain renders per-language thresholds and the structural reason when present`() {
        // number-of-parameters has a per-language Kotlin override.
        CatalogRenderer.explainMetric("number-of-parameters")!! shouldContain "perLanguage:"
        // a kotlin-only lens carries the structural reason it does not fire on Java.
        CatalogRenderer.explainMetric("not-null-assertion-density")!! shouldContain "structuralReason"
    }
}
