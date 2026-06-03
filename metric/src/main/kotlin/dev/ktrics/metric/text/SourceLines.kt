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

    // ktrics:dismiss cyclomatic-complexity reason="hand-rolled block-comment state machine; the branches are the states (in-block, line-comment start, block-comment open/close) and splitting them across helpers would obscure the single character-scan invariant"

    /** Returns (lineContainsCode, inBlockCommentAfterLine). */
    private fun lineHasCode(
        line: String,
        startInBlock: Boolean,
    ): Pair<Boolean, Boolean> {
        var inBlock = startInBlock
        var sawCode = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            val next = line.getOrNull(i + 1)
            when {
                inBlock -> {
                    if (c == '*' && next == '/') {
                        inBlock = false
                        i += 2
                        continue
                    }
                    i++
                }
                c == '/' && next == '/' -> return sawCode to false // rest of line is a line comment
                c == '/' && next == '*' -> {
                    inBlock = true
                    i += 2
                    continue
                }
                !c.isWhitespace() -> {
                    sawCode = true
                    i++
                }
                else -> i++
            }
        }
        return sawCode to inBlock
    }
}
