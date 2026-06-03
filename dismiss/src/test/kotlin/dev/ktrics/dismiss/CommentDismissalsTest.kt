package dev.ktrics.dismiss

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CommentDismissalsTest {
    @Test
    fun `directive directly above the declaration matches`() {
        val src =
            """
            // ktrics:dismiss cyclomatic-complexity reason="state machine"
            fun parse() {}
            """.trimIndent()
        // `fun parse()` is on line 2.
        val d = CommentDismissals(src).forDeclaration(declLine = 2, metric = "cyclomatic-complexity")
        d.shouldNotBeNull()
        d.reason shouldBe "state machine"
    }

    @Test
    fun `a blank line between directive and declaration invalidates it`() {
        val src =
            """
            // ktrics:dismiss cyclomatic-complexity reason="state machine"

            fun parse() {}
            """.trimIndent()
        CommentDismissals(src).forDeclaration(declLine = 3, metric = "cyclomatic-complexity").shouldBeNull()
    }

    @Test
    fun `metric-less directive dismisses any metric on the declaration`() {
        val src =
            """
            // ktrics:dismiss reason="generated-like; reviewed"
            fun parse() {}
            """.trimIndent()
        CommentDismissals(src).forDeclaration(2, "cognitive-complexity").shouldNotBeNull()
    }

    @Test
    fun `a directive for a different metric does not match`() {
        val src =
            """
            // ktrics:dismiss cognitive-complexity reason="ok"
            fun parse() {}
            """.trimIndent()
        CommentDismissals(src).forDeclaration(2, "cyclomatic-complexity").shouldBeNull()
    }

    @Test
    fun `a directive higher in a contiguous comment block still matches`() {
        val src =
            """
            // ktrics:dismiss cyclomatic-complexity reason="state machine"
            // a note on the next line
            // and one more
            fun parse() {}
            """.trimIndent()
        // `fun parse()` is line 4; the directive is three lines up but the block is contiguous (no blank line).
        val d = CommentDismissals(src).forDeclaration(declLine = 4, metric = "cyclomatic-complexity")
        d.shouldNotBeNull()
        d.reason shouldBe "state machine"
    }

    @Test
    fun `a directive inside a block comment continuation is recognised`() {
        val src =
            """
            /*
             * ktrics:dismiss lcom4 reason="value carrier"
             */
            class Carrier
            """.trimIndent()
        // `class Carrier` is line 4; the directive line begins with `*` (a block-comment continuation).
        CommentDismissals(src).forDeclaration(declLine = 4, metric = "lcom4").shouldNotBeNull()
    }

    @Test
    fun `parseLine extracts an optional metric and the reason, guarding the metric-less form`() {
        DismissSyntax.parseLine("""// ktrics:dismiss reason="hi"""") shouldBe (null to "hi")
        DismissSyntax.parseLine("""// ktrics:dismiss lcom4 reason="y"""") shouldBe ("lcom4" to "y")
        DismissSyntax.parseLine("""// ktrics:dismiss   reason  =  "spaced"""") shouldBe (null to "spaced")
        DismissSyntax.parseLine("just a comment, no directive").shouldBeNull()
        DismissSyntax.parseLine("// ktrics:dismiss lcom4").shouldBeNull() // no reason="…" → not a directive match
    }

    @Test
    fun `a code line directly above the declaration stops the scan`() {
        val src =
            """
            val x = 1
            fun parse() {}
            """.trimIndent()
        // The line above is code, not a comment → no dismissal can attach.
        CommentDismissals(src).forDeclaration(2, "cyclomatic-complexity").shouldBeNull()
    }

    @Test
    fun `sidecar parses dismissals and minReasonLength`() {
        val yaml =
            """
            minReasonLength: 8
            dismissals:
              - id: a1b2c3d4e5f60718
                reason: "long enough reason"
              - metric: lcom4
                scope: com.x.Foo
                reason: short
            """.trimIndent()
        val sidecar = Sidecar.parse(yaml)
        sidecar.minReasonLength shouldBe 8
        sidecar.dismissals.size shouldBe 2
        sidecar.dismissals.first().id shouldBe "a1b2c3d4e5f60718"
    }
}
