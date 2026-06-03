package dev.ktrics.engine

import dev.ktrics.config.KtricsConfig
import dev.ktrics.frontend.java.JavaClassifier
import dev.ktrics.frontend.kotlin.KotlinClassifier
import dev.ktrics.frontend.kotlin.ResolvedKotlinClassifier
import dev.ktrics.ir.Lang
import dev.ktrics.module.ModuleGraph
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File
import dev.ktrics.config.UnusedConfig as ConfigUnusedConfig

/** Per-language classifier dispatch + unused-config derivation. */
class ProjectInputsTest {
    private val root = File(".")

    @Test
    fun `classifierFor dispatches kotlin and java to their own classifiers`() {
        val select = ProjectInputs.classifierFor(root, resolved = false)
        select(Lang.KOTLIN).shouldBeInstanceOf<KotlinClassifier>()
        select(Lang.JAVA).shouldBeInstanceOf<JavaClassifier>()
    }

    @Test
    fun `resolved=true selects the resolution-backed classifiers`() {
        val select = ProjectInputs.classifierFor(root, resolved = true)
        select(Lang.KOTLIN).shouldBeInstanceOf<ResolvedKotlinClassifier>()
    }

    @Test
    fun `the same classifier instance is reused across calls for a language`() {
        val select = ProjectInputs.classifierFor(root, resolved = false)
        (select(Lang.KOTLIN) === select(Lang.KOTLIN)) shouldBe true
    }

    private fun project(config: KtricsConfig) = ResolvedProject(graph = ModuleGraph.singleModule(listOf("src")), config = config)

    @Test
    fun `unusedConfig always includes main as an entry point and expands presets`() {
        val resolved = project(KtricsConfig(unused = ConfigUnusedConfig(entryPoints = listOf("@Test"), presets = listOf("spring"))))
        val cfg = ProjectInputs.unusedConfig(resolved)
        cfg.entryPoints shouldContain "main"
        cfg.entryPoints shouldContain "@Test"
        cfg.keepAliveAnnotations shouldContain "Component"
    }

    @Test
    fun `includeTests clears the test globs so the sweep widens into test trees`() {
        val resolved = project(KtricsConfig())
        ProjectInputs.unusedConfig(resolved, includeTests = false).testGlobs.isEmpty() shouldBe false
        ProjectInputs.unusedConfig(resolved, includeTests = true).testGlobs shouldBe emptyList()
    }
}
