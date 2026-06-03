package dev.ktrics.ir

import kotlinx.serialization.Serializable

/**
 * A source range. Lines and columns are 1-based (editor convention); offsets are 0-based document
 * offsets (PSI convention). Carried into every [Violation] so the `ai`/`sarif`/`md` reporters can
 * point at the exact declaration and slice `line ± 3` snippets.
 */
@Serializable
data class Span(
    val file: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val startOffset: Int,
    val endOffset: Int,
) {
    /** Inclusive line count of the span; the natural denominator for body-length metrics. */
    val lineCount: Int get() = endLine - startLine + 1

    companion object {
        /** A synthetic span for whole-file lenses (e.g. top-level-declarations-per-file). */
        fun wholeFile(
            file: String,
            lastLine: Int,
            lastOffset: Int,
        ): Span = Span(file, 1, 1, lastLine.coerceAtLeast(1), 1, 0, lastOffset)
    }
}
