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
}
