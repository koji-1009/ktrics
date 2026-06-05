package dev.ktrics.vcs

import java.io.File
import java.util.concurrent.TimeUnit

/** Raised when a git ref cannot be resolved — the caller maps this to exit 65. */
class GitRefException(message: String) : Exception(message)

/**
 * Thin git shell-out. Identical `--since`/snapshot/regression semantics to the sibling
 * tools. The daemon keeps these warm. No JGit dependency: a `git` on PATH is assumed (relayed in the
 * client env).
 */
class GitClient(
    private val workTree: File,
    /** Per-call git timeout; injectable so the deadlock-guard branch is testable without a real hang. */
    private val timeoutSeconds: Long = GIT_TIMEOUT_SECONDS,
    /** Process spawn seam (mirrors DaemonLauncher's): the default shells out to `git` in [workTree]. */
    private val spawn: (List<String>) -> Process = { argv ->
        ProcessBuilder(argv)
            .directory(workTree)
            // Drain stderr at the OS level: with a separate, undrained stderr pipe a chatty git can
            // fill the pipe buffer and block on write while we block reading stdout — a deadlock. We
            // only ever consume stdout, so discarding stderr matches the existing behaviour.
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    },
) {
    fun isRepository(): Boolean = run("rev-parse", "--is-inside-work-tree").trim() == "true"

    /** The repository toplevel directory, or null outside a repository. */
    fun topLevel(): File? {
        val (out, code) = runChecked("rev-parse", "--show-toplevel")
        return out.trim().takeIf { code == 0 && it.isNotEmpty() }?.let(::File)
    }

    /** True when the working tree has no uncommitted changes — `unused --apply` requires this. */
    fun isClean(): Boolean = run("status", "--porcelain").isBlank()

    /** Resolves a ref to a commit sha, or throws [GitRefException] (→ exit 65). */
    fun resolve(ref: String): String {
        val (out, code) = runChecked("rev-parse", "--verify", "$ref^{commit}")
        if (code != 0) throw GitRefException("unresolved git ref: $ref")
        return out.trim()
    }

    /**
     * Working-tree line ranges changed since [ref], keyed by file path RELATIVE TO the [workTree]
     * directory (`--relative` — so a project rooted in a repo subdirectory keys match the analyzer's
     * projectRoot-relative paths, and changes outside the subtree are excluded). `-U0` hunks are in
     * working-tree coordinates, ready to intersect with scope spans; `-M` folds a pure rename into a
     * hunk-less entry that surfaces nothing (dartrics 1.1.0 semantics); the pathspec keeps the diff to
     * the source files the analyzer can attribute. A deletion-only hunk marks the single join line.
     */
    fun changedLineRangesSince(ref: String): Map<String, List<IntRange>> {
        resolve(ref)
        val diff =
            run(
                // Unquoted (raw) paths so a non-ASCII filename matches the analyzer's path string.
                "-c", "core.quotePath=false",
                "diff", "--relative", "--unified=0", "-M", "--diff-filter=AMR", ref, "--", "*.kt", "*.java",
            )
        return parseUnifiedZeroRanges(diff)
    }

    /** Materialises [ref] into a detached worktree at [dir] (for analyzing a past revision). */
    fun addWorktree(
        dir: File,
        ref: String,
    ) {
        resolve(ref)
        val (_, code) = runChecked("worktree", "add", "--detach", dir.absolutePath, ref)
        if (code != 0) throw GitRefException("could not add worktree for $ref")
    }

    /** Removes a worktree created by [addWorktree]. */
    fun removeWorktree(dir: File) {
        runChecked("worktree", "remove", "--force", dir.absolutePath)
    }

    private fun run(vararg args: String): String = runChecked(*args).first

    private fun runChecked(vararg args: String): Pair<String, Int> {
        val process = spawn(listOf("git", *args))
        // Read stdout on a separate thread so the timeout below can actually fire even if git keeps
        // its stdout open; otherwise readText() would block indefinitely before we ever wait.
        val stdout = arrayOfNulls<String>(1)
        val reader =
            Thread {
                stdout[0] = process.inputStream.bufferedReader().readText()
            }.apply {
                isDaemon = true
                start()
            }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            reader.join(JOIN_TIMEOUT_MS)
            return (stdout[0] ?: "") to TIMED_OUT_CODE
        }
        // The process has exited, so its stdout is at (or racing to) EOF — join UNBOUNDED so a large
        // `git diff` is read in full. A bounded join here truncated big output to "" and made the
        // caller (e.g. changedLineRangesSince) report 'no changes' on a successful command.
        reader.join()
        return (stdout[0] ?: "") to process.exitValue()
    }

    private companion object {
        const val GIT_TIMEOUT_SECONDS = 30L
        const val JOIN_TIMEOUT_MS = 1_000L

        /** Non-zero sentinel so a timed-out git is treated as failure (e.g. an unresolved ref → exit 65). */
        const val TIMED_OUT_CODE = 124
    }
}

/**
 * Parses unified-zero diff output into `new-file path → +c,d ranges` (1-based, inclusive). Pure so
 * the hunk grammar is testable without a repository. A `+c,0` hunk is a pure deletion: it touches no
 * current line but the join point is where the change "is" — mapped to the single line `max(c, 1)`
 * (c is 0 when the deletion is at the top of the file).
 */
internal fun parseUnifiedZeroRanges(diff: String): Map<String, List<IntRange>> {
    val ranges = LinkedHashMap<String, MutableList<IntRange>>()
    var current: String? = null
    for (line in diff.lineSequence()) {
        when {
            line.startsWith("+++ ") -> {
                val path = line.removePrefix("+++ ").trim()
                current = if (path == "/dev/null") null else path.removePrefix("b/")
            }
            line.startsWith("@@") -> {
                val file = current ?: continue
                val range = parseHunkPlusRange(line) ?: continue
                ranges.getOrPut(file) { ArrayList() }.add(range)
            }
        }
    }
    return ranges
}

/** The `+c[,d]` side of one `@@ -a[,b] +c[,d] @@` header as an inclusive line range, or null if malformed. */
private fun parseHunkPlusRange(header: String): IntRange? {
    val plus = header.split(' ').firstOrNull { it.startsWith("+") } ?: return null
    val start = plus.removePrefix("+").substringBefore(',').toIntOrNull() ?: return null
    val count = plus.substringAfter(',', "1").toIntOrNull() ?: return null
    return if (count == 0) {
        val at = maxOf(start, 1) // pure deletion: mark the join line
        at..at
    } else {
        start..(start + count - 1)
    }
}
