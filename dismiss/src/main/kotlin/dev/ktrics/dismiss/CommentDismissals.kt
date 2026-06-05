package dev.ktrics.dismiss

/**
 * Scans the comment dismissal channel: a `// ktrics:dismiss <metric> reason="..."` directly
 * above the declaration, in the contiguous comment block with NO blank line between it and the
 * declaration (a blank line invalidates the dismissal).
 */
class CommentDismissals(text: String) {
    private val lines: List<String> = text.split("\n")

    /**
     * A dismissal for [metric] applying to the declaration whose span starts at 1-based [declLine], or null.
     *
     * [declLine] is the *span* start, which includes the declaration's leading KDoc/annotations, so the
     * directive — conventionally placed directly above the `fun`/`class` keyword, i.e. BELOW the KDoc — sits
     * INSIDE the span. We therefore skip the leading trivia down to the keyword line, then scan UPWARD through
     * the whole contiguous comment/annotation preface: a directive between the KDoc and the keyword is found,
     * and one above the KDoc is found too. A blank line (or non-comment code) ends the contiguous block.
     */
    fun forDeclaration(
        declLine: Int,
        metric: String,
    ): Dismissal? = matchInPreface(declLine, metric)?.second

    /**
     * The 1-based line of the directive [forDeclaration] would consume for ([declLine], [metric]), or
     * null. Stale detection diffs the consumed lines against [allDirectives].
     */
    fun directiveLineFor(
        declLine: Int,
        metric: String,
    ): Int? = matchInPreface(declLine, metric)?.first

    /** Every directive in the file with its 1-based line — consumed or not (stale detection input). */
    fun allDirectives(): List<CommentDirective> =
        lines.mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (!DismissSyntax.isDirectiveLine(line)) return@mapIndexedNotNull null
            DismissSyntax.parseLine(line)?.let { (metric, reason) -> CommentDirective(index + 1, metric, reason) }
        }

    private fun matchInPreface(
        declLine: Int,
        metric: String,
    ): Pair<Int, Dismissal>? {
        val keywordIndex = keywordIndex(declLine - 1) // 0-based index of the declaration's keyword line
        var index = keywordIndex - 1 // start directly above the keyword
        while (index >= 0) {
            val line = lines[index].trim()
            when {
                line.isEmpty() -> return null // blank line invalidates
                line.startsWith("@") -> index-- // annotation in the preface — keep scanning upward past it
                isComment(line) -> {
                    dismissalIn(line, metric)?.let { return (index + 1) to it }
                    index-- // not this metric's directive: keep scanning the contiguous comment block upward
                }
                else -> return null // hit code; the directive (if any) wasn't in the preface
            }
        }
        return null
    }

    /** The dismissal one comment line carries for [metric]; null when it is no directive or targets another metric. */
    private fun dismissalIn(
        line: String,
        metric: String,
    ): Dismissal? {
        if (!DismissSyntax.isDirectiveLine(line)) return null
        val (m, reason) = DismissSyntax.parseLine(line) ?: return null
        return if (m == null || m == metric) Dismissal(reason = reason, source = "comment", metric = m) else null
    }

    /**
     * From the span-start line [spanStartIndex] (0-based), skip the declaration's leading trivia — KDoc/block
     * comments and `@Annotation` lines — to the keyword line. Blank lines inside the trivia are tolerated (an
     * annotation may sit a line above the keyword); the first non-trivia, non-blank line is the keyword.
     */
    private fun keywordIndex(spanStartIndex: Int): Int {
        var index = spanStartIndex
        while (index < lines.size) {
            val line = lines[index].trim()
            when {
                line.isEmpty() -> index++ // blank within the leading trivia
                isComment(line) -> index++ // KDoc / block-comment / `//` line in the preface
                line.startsWith("@") -> index++ // annotation line
                else -> return index // the keyword line (e.g. `fun`/`class`/visibility modifier)
            }
        }
        return index.coerceAtMost(lines.size - 1)
    }

    private fun isComment(trimmed: String): Boolean = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
}
