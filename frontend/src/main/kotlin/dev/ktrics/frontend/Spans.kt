package dev.ktrics.frontend

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.ktrics.ir.Span

/**
 * Computes 1-based line/column [Span]s from PSI offsets. A standalone session may not expose a
 * Document, so line/column are derived from the file text directly — line starts are computed once
 * per file and cached, then offsets are binary-searched against them.
 */
class SpanFactory(private val file: PsiFile, private val relativePath: String) {
    private val text: CharSequence = file.viewProvider.contents
    private val lineStarts: IntArray = computeLineStarts(text)

    fun of(element: PsiElement): Span {
        // Synthetic/implicit PSI (e.g. a Java record's generated accessors and canonical constructor)
        // has no source range; a span factory must degrade to a zero-length file-start span rather than
        // NPE, or lowering crashes on any file that declares a record.
        val range = element.textRange ?: return Span(relativePath, 1, 1, 1, 1, 0, 0)
        val (sl, sc) = lineCol(range.startOffset)
        val (el, ec) = lineCol(range.endOffset)
        return Span(relativePath, sl, sc, el, ec, range.startOffset, range.endOffset)
    }

    fun wholeFile(): Span {
        val end = text.length
        val (el, ec) = lineCol(end)
        return Span(relativePath, 1, 1, el, ec, 0, end)
    }

    private fun lineCol(offset: Int): Pair<Int, Int> {
        val clamped = offset.coerceIn(0, text.length)
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= clamped) lo = mid else hi = mid - 1
        }
        return (lo + 1) to (clamped - lineStarts[lo] + 1)
    }

    private fun computeLineStarts(text: CharSequence): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
        return starts.toIntArray()
    }
}
