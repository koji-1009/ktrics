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

    /** A stub PsiElement: unusedConfig only reads `imports`, so no method on it is ever invoked. */
    private fun stubNode(): com.intellij.psi.PsiElement =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.intellij.psi.PsiElement::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                else -> null
            }
        } as com.intellij.psi.PsiElement

    private fun unitWithImports(vararg imports: String) =
        dev.ktrics.ir.SourceUnit(
            path = "src/X.kt",
            lang = Lang.KOTLIN,
            packageName = "x",
            imports = imports.toList(),
            types = emptyList(),
            topLevelFns = emptyList(),
            topLevelProps = emptyList(),
            span = dev.ktrics.ir.Span("src/X.kt", 1, 1, 1, 1, 0, 0),
            node = stubNode(),
        )

    @Test
    fun `unusedConfig always includes main as an entry point and expands presets`() {
        val resolved = project(KtricsConfig(unused = ConfigUnusedConfig(entryPoints = listOf("@Test"), presets = listOf("spring"))))
        val cfg = ProjectInputs.unusedConfig(resolved, units = emptyList())
        cfg.entryPoints shouldContain "main"
        cfg.entryPoints shouldContain "@Test"
        cfg.keepAliveAnnotations shouldContain "Component"
    }

    @Test
    fun `imports auto-enable the matching presets`() {
        val resolved = project(KtricsConfig())
        val units = listOf(unitWithImports("androidx.appcompat.app.AppCompatActivity", "org.springframework.stereotype.Service"))
        val cfg = ProjectInputs.unusedConfig(resolved, units)
        cfg.keepAliveSupertypes shouldContain "Activity" // android detected from androidx.*
        cfg.keepAliveAnnotations shouldContain "Component" // spring detected from org.springframework.*
        cfg.keepAliveAnnotations shouldContain "Keep"
    }

    @Test
    fun `auto-presets false keeps detection off and non-framework imports detect nothing`() {
        val androidUnits = listOf(unitWithImports("androidx.appcompat.app.AppCompatActivity"))
        val optedOut = project(KtricsConfig(unused = ConfigUnusedConfig(autoPresets = false)))
        ProjectInputs.unusedConfig(optedOut, androidUnits).keepAliveSupertypes shouldBe emptySet()
        // A plain backend importing neither framework gets no preset surface at all.
        val plain = project(KtricsConfig())
        val cfg = ProjectInputs.unusedConfig(plain, listOf(unitWithImports("java.util.UUID", "kotlin.io.path.Path")))
        cfg.keepAliveSupertypes shouldBe emptySet()
        cfg.keepAliveAnnotations shouldBe emptySet()
    }

    @Test
    fun `includeTests clears the test globs so the sweep widens into test trees`() {
        val resolved = project(KtricsConfig())
        ProjectInputs.unusedConfig(resolved, units = emptyList(), includeTests = false).testGlobs.isEmpty() shouldBe false
        ProjectInputs.unusedConfig(resolved, units = emptyList(), includeTests = true).testGlobs shouldBe emptyList()
    }
}
