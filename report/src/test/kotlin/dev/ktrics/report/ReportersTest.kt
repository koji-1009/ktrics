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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class ReportersTest {
    private fun violation(
        metric: String,
        severity: Severity,
        value: Double,
        threshold: Double,
        lang: Lang = Lang.KOTLIN,
        resolution: Resolution? = null,
        line: Int = 10,
    ): Violation {
        val span = Span("src/Foo.kt", line, 1, line, 10, 0, 0)
        return Violation(
            id = StableId.of("src/Foo.kt", "com.x.Foo.$metric", metric),
            metricId = metric, severity = severity, polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH, file = "src/Foo.kt", scope = "com.x.Foo.$metric",
            scopeName = "Foo.$metric", scopeKind = ScopeKind.FUNCTION, lang = lang, value = value,
            threshold = threshold, resolution = resolution, span = span,
            rationale = "why it matters", refactorHints = listOf("do this"), references = listOf("Paper 1"),
        )
    }

    private fun report(vararg v: Violation): AnalysisReport {
        val files = listOf(FileEntry("src/Foo.kt", Lang.KOTLIN, v.size))
        return AnalysisReport(
            version = "test",
            root = "/p",
            summary = ReportSummary.of(v.toList(), files),
            violations = v.toList(),
            files = files,
        )
    }

    @Test
    fun `actionability puts errors first then larger breaches`() {
        val warnSmall = violation("cognitive-complexity", Severity.WARNING, 16.0, 15.0)
        val warnBig = violation("cyclomatic-complexity", Severity.WARNING, 40.0, 10.0)
        val error = violation("npath-complexity", Severity.ERROR, 250.0, 200.0)
        val sorted = Actionability.sort(listOf(warnSmall, warnBig, error))
        sorted.first().severity shouldBe Severity.ERROR
        sorted[1].metricId shouldBe "cyclomatic-complexity" // 4× breach outranks 1.07× breach
    }

    @Test
    fun `ai reporter emits the contractual header and per-violation language and resolution`() {
        val text =
            AiReporter().render(
                report(violation("coupling-between-objects", Severity.WARNING, 25.0, 20.0, resolution = Resolution.NAME_BASED)),
            )
        text shouldStartWith AnalysisReport.AI_HEADER
        text shouldContain "language: kotlin"
        text shouldContain "resolution: name-based"
        text shouldContain "rationale:"
    }

    @Test
    fun `no-auto-explain omits rationale, refactor hints and references entirely`() {
        // The violation carries hints and references; --no-auto-explain must drop the whole explain block,
        // and the default run must include it (the contrast proves the flag does something, not a typo).
        val v = violation("lcom4", Severity.WARNING, 3.0, 2.0)
        val plain = AiReporter(autoExplain = false).render(report(v))
        plain.contains("rationale:") shouldBe false
        plain.contains("refactorHints:") shouldBe false
        plain.contains("references:") shouldBe false
        val explained = AiReporter(autoExplain = true).render(report(v))
        explained shouldContain "rationale:"
        explained shouldContain "refactorHints:"
        explained shouldContain "references:"
    }

    @Test
    fun `sarif renders a structurally valid 2-1-0 document with the result fully pinned`() {
        val v = violation("cyclomatic-complexity", Severity.ERROR, 30.0, 20.0, resolution = Resolution.NAME_BASED)
        val rpt = report(v)
        // parseToJsonElement throws on malformed JSON — so this also proves the output is well-formed.
        val root = Json.parseToJsonElement(SarifReporter().render(rpt)).jsonObject

        root["version"]!!.jsonPrimitive.content shouldBe "2.1.0"
        root.containsKey("\$schema") shouldBe true
        val run = root["runs"]!!.jsonArray.single().jsonObject
        val driver = run["tool"]!!.jsonObject["driver"]!!.jsonObject
        driver["name"]!!.jsonPrimitive.content shouldBe rpt.tool
        // The rules array mirrors the builtin catalogue, not an empty/malformed list.
        driver["rules"]!!.jsonArray.size shouldBe BuiltinMetrics.all.size

        val result = run["results"]!!.jsonArray.single().jsonObject
        result["ruleId"]!!.jsonPrimitive.content shouldBe "cyclomatic-complexity"
        result["level"]!!.jsonPrimitive.content shouldBe "error"
        val region =
            result["locations"]!!.jsonArray.single().jsonObject["physicalLocation"]!!.jsonObject["region"]!!.jsonObject
        region["startLine"]!!.jsonPrimitive.int shouldBe v.span.startLine
        region["startColumn"]!!.jsonPrimitive.int shouldBe v.span.startColumn
        region["endLine"]!!.jsonPrimitive.int shouldBe v.span.endLine
        // The stable id under the locked fingerprint key — the cross-run "fix didn't take" contract.
        result["partialFingerprints"]!!.jsonObject[StableId.SARIF_FINGERPRINT_KEY]!!.jsonPrimitive.content shouldBe v.id
        result["properties"]!!.jsonObject["resolution"]!!.jsonPrimitive.content shouldBe "name-based"
    }

    @Test
    fun `a warning violation maps to the SARIF warning level`() {
        val root = Json.parseToJsonElement(SarifReporter().render(report(violation("lcom4", Severity.WARNING, 3.0, 2.0)))).jsonObject
        val result = root["runs"]!!.jsonArray.single().jsonObject["results"]!!.jsonArray.single().jsonObject
        result["level"]!!.jsonPrimitive.content shouldBe "warning"
    }

    @Test
    fun `json re-emit round-trips every field and pins the wire key names`() {
        val original = report(violation("cyclomatic-complexity", Severity.ERROR, 30.0, 20.0, resolution = Resolution.NAME_BASED))
        val json = JsonReporter().render(original)
        // Full-object equality: every field survives the round-trip, not just `id`.
        JsonReporter.parse(json) shouldBe original
        // Pin the actual wire key names so a rename breaks the test (the parser alone can't catch that).
        val v0 = Json.parseToJsonElement(json).jsonObject["violations"]!!.jsonArray.single().jsonObject
        listOf("id", "metricId", "severity", "value", "threshold", "scope", "span").forEach {
            v0.containsKey(it) shouldBe true
        }
        v0["metricId"]!!.jsonPrimitive.content shouldBe "cyclomatic-complexity"
        v0["value"]!!.jsonPrimitive.double shouldBe 30.0
    }
}
