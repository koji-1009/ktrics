package dev.ktrics.ir

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Span line math is the denominator for body-length metrics. */
class SpanTest {
    private fun span(
        startLine: Int,
        endLine: Int,
    ) = Span("F.kt", startLine, 1, endLine, 1, 0, 0)

    @Test
    fun `lineCount is inclusive of both endpoints`() {
        span(10, 12).lineCount shouldBe 3
    }

    @Test
    fun `a single-line span counts as one line`() {
        span(7, 7).lineCount shouldBe 1
    }

    @Test
    fun `wholeFile spans line 1 to the last line`() {
        val s = Span.wholeFile("Big.kt", lastLine = 120, lastOffset = 4096)
        s.file shouldBe "Big.kt"
        s.startLine shouldBe 1
        s.startColumn shouldBe 1
        s.endLine shouldBe 120
        s.startOffset shouldBe 0
        s.endOffset shouldBe 4096
        s.lineCount shouldBe 120
    }

    @Test
    fun `wholeFile coerces a zero or negative last line up to 1`() {
        // An empty file still has a single synthetic line so the span never inverts.
        Span.wholeFile("Empty.kt", lastLine = 0, lastOffset = 0).endLine shouldBe 1
        Span.wholeFile("Empty.kt", lastLine = -5, lastOffset = 0).lineCount shouldBe 1
    }
}
