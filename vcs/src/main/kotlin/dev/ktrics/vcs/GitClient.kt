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

    /** True when the working tree has no uncommitted changes — `unused --apply` requires this. */
    fun isClean(): Boolean = run("status", "--porcelain").isBlank()

    /** Resolves a ref to a commit sha, or throws [GitRefException] (→ exit 65). */
    fun resolve(ref: String): String {
        val (out, code) = runChecked("rev-parse", "--verify", "$ref^{commit}")
        if (code != 0) throw GitRefException("unresolved git ref: $ref")
        return out.trim()
    }

    /** Files changed between [ref] and the working tree (for `--since`). */
    fun changedFilesSince(ref: String): List<String> {
        resolve(ref)
        return run("diff", "--name-only", ref, "--").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
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
        // caller (e.g. changedFilesSince) report 'no changes' on a successful command.
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
