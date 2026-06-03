package dev.ktrics.ir

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Stable-key derivation for resolved vs name-based references. */
class RefsTest {
    @Test
    fun `TypeRef key prefers the qualified name when resolved`() {
        val ref =
            TypeRef(
                name = "List",
                qualifiedName = "java.util.List",
                packageName = "java.util",
                resolution = Resolution.RESOLVED,
            )
        ref.key shouldBe "java.util.List"
    }

    @Test
    fun `TypeRef key falls back to the simple name when name-based`() {
        val ref =
            TypeRef(
                name = "List",
                qualifiedName = null,
                packageName = null,
                resolution = Resolution.NAME_BASED,
            )
        ref.key shouldBe "List"
    }

    @Test
    fun `SymbolRef key qualifies with the container when known`() {
        val ref = SymbolRef(name = "add", container = "java.util.List", resolution = Resolution.RESOLVED)
        ref.key shouldBe "java.util.List.add"
    }

    @Test
    fun `SymbolRef key is the bare name when the container is unknown`() {
        val ref = SymbolRef(name = "add", container = null, resolution = Resolution.NAME_BASED)
        ref.key shouldBe "add"
    }

    @Test
    fun `TypeRef defaults to non-external`() {
        TypeRef("T", null, null, Resolution.NAME_BASED).external shouldBe false
    }

    @Test
    fun `a Halstead token carries its lexeme and operator-operand classification`() {
        val op = Token("+", TokenKind.OPERATOR)
        val operand = Token("x", TokenKind.OPERAND)
        op.text shouldBe "+"
        op.kind shouldBe TokenKind.OPERATOR
        operand.kind shouldBe TokenKind.OPERAND
    }
}
