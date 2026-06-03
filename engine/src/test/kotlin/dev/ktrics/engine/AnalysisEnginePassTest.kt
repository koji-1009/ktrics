package dev.ktrics.engine

import dev.ktrics.module.ModuleGraph
import dev.ktrics.testsession.repoRoot
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** The full metric pass (function/type/file/package) and the exclude-globs filter. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalysisEnginePassTest {
    private val projectRoot = repoRoot().resolve("testdata/analyze")
    private val graph = ModuleGraph.singleModule(srcRoots = listOf("src/main/kotlin"))

    @AfterAll
    fun tearDown() = WarmIndexCache.clear()

    @Test
    fun `includeMeasurements emits function, file and package level measurements`() {
        val report = AnalysisEngine(projectRoot = projectRoot, includeMeasurements = true).analyze(graph)
        val ids = report.measurements.map { it.metricId }.toSet()
        ids.contains("cyclomatic-complexity") shouldBe true // function level
        ids.contains("types-per-file") shouldBe true // file level (Kotlin)
        ids.contains("efferent-coupling") shouldBe true // package level (Martin)
    }

    @Test
    fun `an exclude glob removes a file from the measured set`() {
        // Excluding the only source file leaves nothing to violate.
        val report = AnalysisEngine(projectRoot = projectRoot, excludeGlobs = listOf("**/Tangled.kt")).analyze(graph)
        report.violations.none { it.file.endsWith("Tangled.kt") } shouldBe true
        report.files.none { it.path.endsWith("Tangled.kt") } shouldBe true
    }
}
