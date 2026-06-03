package dev.ktrics.engine

import dev.ktrics.module.ModuleGraph
import dev.ktrics.report.AiReporter
import dev.ktrics.report.AnalysisReport
import dev.ktrics.testsession.repoRoot
import dev.ktrics.testsupport.GoldenAssert
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * End-to-end: a real session over a small fixture, through the full analyze pipeline, to a stable
 * golden of the `ai` report. Pins the contract surface an agent consumes — the header,
 * the summary, and the known violation — against drift, with volatile bits (root, version) normalized.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngineAnalyzeGoldenTest {
    private lateinit var report: AnalysisReport

    @BeforeAll
    fun setUp() {
        val projectRoot = repoRoot().resolve("testdata/analyze")
        val graph = ModuleGraph.singleModule(srcRoots = listOf("src/main/kotlin"))
        report = AnalysisEngine(projectRoot = projectRoot).analyze(graph)
    }

    @AfterAll
    fun tearDown() {
        // The warm index cache holds the session; drop it so the platform disposes.
        WarmIndexCache.clear()
    }

    @Test
    fun `the tangled method breaches cyclomatic complexity`() {
        val cyclo = report.violations.filter { it.metricId == "cyclomatic-complexity" }
        cyclo.map { it.scope } shouldContain "sample.Tangled.tangled"
        cyclo.single { it.scope == "sample.Tangled.tangled" }.value shouldBe 11.0
    }

    @Test
    fun `the clean method produces no violation`() {
        report.violations.none { it.scope == "sample.Tangled.clean" } shouldBe true
    }

    @Test
    fun `the ai report matches the golden`() {
        val rendered = AiReporter().render(report)
        GoldenAssert.assertMatches("engine-analyze-ai.txt", normalize(rendered))
    }

    /** Replaces machine/version-specific lines so the golden is portable. */
    private fun normalize(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            when {
                line.startsWith("tool: ") -> "tool: ktrics <VERSION>"
                line.startsWith("root: ") -> "root: <ROOT>"
                else -> line
            }
        }
}
