package dev.ktrics.metric

import dev.ktrics.metric.text.SourceLines
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceLinesTest {
    @Test
    fun `counts code lines, skipping blanks and comments`() {
        val code =
            """
            {
                // a line comment
                int x = 1;

                /* block
                   comment */
                return x;
            }
            """.trimIndent()
        // Code lines: `{`, `int x = 1;`, `return x;`, `}` = 4.
        SourceLines.count(code) shouldBe 4
    }

    @Test
    fun `code after a block comment on the same line counts`() {
        SourceLines.count("/* doc */ doWork();") shouldBe 1
    }

    @Test
    fun `empty body is zero`() {
        SourceLines.count("\n\n   \n") shouldBe 0
    }

    @Test
    fun `an open marker reusing its star never closes itself`() {
        // In `/*/` the trailing slash belongs to the OPEN marker's span: the scan must advance past
        // both opener characters before searching for a close, so the line stays inside the comment.
        SourceLines.count("/*/ still a comment") shouldBe 0
    }

    @Test
    fun `a line-comment marker inside a block comment does not end the line`() {
        // The `//` is commented out; the code after the block close still counts.
        SourceLines.count("/* // */ doWork();") shouldBe 1
    }
}
