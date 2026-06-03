package dev.ktrics.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Validates the repository's own `ktrics.yaml` (dogfood): the declared 16-module graph
 * must be a valid DAG with known metric ids, so `ktrics doctor` would report no errors. This guards
 * the self-application config against drift (a typo'd module name or a dependency cycle).
 */
class DogfoodConfigTest {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate repo root")
    }

    @Test
    fun `the dogfood config loads and doctor reports no errors`() {
        val root = repoRoot()
        val load = ConfigLoader.load(root)
        // The file exists and parsed without loader problems.
        (load.source != null) shouldBe true
        load.problems shouldBe emptyList()

        val errors = Doctor.check(load).filter { it.severity == Doctor.Severity.ERROR }
        errors shouldBe emptyList()
    }

    @Test
    fun `the declared graph covers the production modules and builds without a cycle`() {
        val config = ConfigLoader.load(repoRoot()).config
        val graph = config.modules.toGraph()!!
        val names = graph.modules.map { it.name }.toSet()
        // Spot-check the leaves and the roots of the dependency graph.
        names.containsAll(listOf("ir", "metric", "engine", "daemon", "frontend-kotlin")) shouldBe true
        graph.modules.size shouldBe 16
    }
}
