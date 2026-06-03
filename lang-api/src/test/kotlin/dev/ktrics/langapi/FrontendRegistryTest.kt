package dev.ktrics.langapi

import com.intellij.psi.PsiElement
import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
import dev.ktrics.testsupport.FakeClassifier
import dev.ktrics.testsupport.sourceUnit
import dev.ktrics.testsupport.stubNode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The registry resolves the right [LanguageFrontend] per file or per language. */
class FrontendRegistryTest {
    private class FakeFrontend(override val lang: Lang) : LanguageFrontend {
        override val classifier: NodeClassifier = FakeClassifier()

        override fun accepts(file: PsiElement): Boolean = file === marker

        override fun lower(file: PsiElement): SourceUnit = sourceUnit(lang = lang)

        val marker: PsiElement = stubNode()
    }

    private val kotlin = FakeFrontend(Lang.KOTLIN)
    private val java = FakeFrontend(Lang.JAVA)
    private val registry = FrontendRegistry(listOf(kotlin, java))

    @Test
    fun `forLang returns the frontend for a language`() {
        registry.forLang(Lang.KOTLIN) shouldBe kotlin
        registry.forLang(Lang.JAVA) shouldBe java
    }

    @Test
    fun `forFile returns the frontend that accepts the file, or null`() {
        registry.forFile(kotlin.marker) shouldBe kotlin
        registry.forFile(java.marker) shouldBe java
        registry.forFile(stubNode()).shouldBeNull() // accepted by neither
    }

    @Test
    fun `all exposes the registered frontends in order`() {
        registry.all() shouldBe listOf(kotlin, java)
    }
}
