package dev.ktrics.ir

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Visibility normalization drives unused-detection's API surface. */
class ModifiersTest {
    @Test
    fun `public protected and internal are API surface`() {
        Visibility.PUBLIC.isApiSurface shouldBe true
        Visibility.PROTECTED.isApiSurface shouldBe true
        Visibility.INTERNAL.isApiSurface shouldBe true
    }

    @Test
    fun `package-private and private are not API surface`() {
        // private is already covered by the compiler/IDE; package-private is not narrower-public.
        Visibility.PACKAGE_PRIVATE.isApiSurface shouldBe false
        Visibility.PRIVATE.isApiSurface shouldBe false
    }

    @Test
    fun `the PUBLIC constant is a public visibility with no extra flags`() {
        val m = Modifiers.PUBLIC
        m.visibility shouldBe Visibility.PUBLIC
        m.isAbstract shouldBe false
        m.isFinal shouldBe false
        m.raw shouldBe emptySet()
    }

    @Test
    fun `a boolean param is flagged for boolean-trap`() {
        Param(name = "enabled", typeName = "Boolean", isBoolean = true).isBoolean shouldBe true
        Param(name = "count", typeName = "Int").isBoolean shouldBe false
    }
}
