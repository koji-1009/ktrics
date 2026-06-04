package dev.ktrics.metric.text

/**
 * Counts non-blank, non-comment-only source lines (Boehm SLOC). Shared by both languages:
 * a small state machine strips block comments, drops line comments and blank lines.
 *
 * Approximation (documented in doc/calibration.md): a line- or block-comment marker appearing inside
 * a string literal is treated as a comment start. This rarely changes a count and keeps the counter
 * language-neutral and dependency-free; precise tokenization is reserved for Halstead.
 */
object SourceLines {
    fun count(text: String): Int {
        var inBlock = false
        var sloc = 0
        for (rawLine in text.lineSequence()) {
            val (hasCode, blockAfter) = lineHasCode(rawLine, inBlock)
            inBlock = blockAfter
            if (hasCode) sloc++
        }
        return sloc
    }

    /**
     * Returns (lineContainsCode, inBlockCommentAfterLine). Two states: inside a block comment the only
     * interesting position is the close marker (one `indexOf`); outside it the scan watches for the two
     * comment openers and any non-whitespace code character.
     */
    private fun lineHasCode(
        line: String,
        startInBlock: Boolean,
    ): Pair<Boolean, Boolean> {
        var inBlock = startInBlock
        var sawCode = false
        var i = 0
        while (i < line.length) {
            if (inBlock) {
                val close = line.indexOf("*/", i)
                if (close == -1) return sawCode to true // comment swallows the rest of the line
                inBlock = false
                i = close + 2
                continue
            }
            val c = line[i]
            when {
                c == '/' && line.getOrNull(i + 1) == '/' -> return sawCode to false // rest of line is a line comment
                c == '/' && line.getOrNull(i + 1) == '*' -> {
                    inBlock = true
                    i += 2
                }
                else -> {
                    if (!c.isWhitespace()) sawCode = true
                    i++
                }
            }
        }
        return sawCode to inBlock
    }
}
