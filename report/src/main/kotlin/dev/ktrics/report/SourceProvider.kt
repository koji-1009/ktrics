package dev.ktrics.report

import java.io.File

/** Supplies source lines so the `ai`/`console` reporters can slice `line ± 3` snippets. */
interface SourceProvider {
    /** 1-based inclusive line range; out-of-range lines are clamped. Returns (firstLineNo, lines). */
    fun lines(
        file: String,
        from: Int,
        to: Int,
    ): Pair<Int, List<String>>

    object None : SourceProvider {
        override fun lines(
            file: String,
            from: Int,
            to: Int,
        ): Pair<Int, List<String>> = from to emptyList()
    }
}

/** Reads snippets from disk relative to the project root, caching each file's lines once. */
class FileSystemSourceProvider(private val root: File) : SourceProvider {
    private val cache = HashMap<String, List<String>>()

    override fun lines(
        file: String,
        from: Int,
        to: Int,
    ): Pair<Int, List<String>> {
        val all =
            cache.getOrPut(file) {
                val f = File(root, file)
                if (f.isFile) f.readLines() else emptyList()
            }
        if (all.isEmpty()) return from to emptyList()
        val start = (from - 1).coerceIn(0, all.size - 1)
        val end = (to - 1).coerceIn(start, all.size - 1)
        return (start + 1) to all.subList(start, end + 1)
    }
}
