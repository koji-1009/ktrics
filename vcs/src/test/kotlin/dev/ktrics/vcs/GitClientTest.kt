package dev.ktrics.vcs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * GitClient against a real temporary repository. Environment-dependent (needs `git` on
 * PATH) — skipped when git is absent. Exercises the stdout/stderr drain on
 * real `git diff`/`worktree` round-trips.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitClientTest {
    private lateinit var repo: File
    private lateinit var firstSha: String

    private fun git(
        vararg args: String,
        dir: File = repo,
    ): String {
        val process =
            ProcessBuilder(
                listOf("git", "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false", *args),
            ).directory(dir).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return out.trim()
    }

    private fun gitAvailable(): Boolean =
        runCatching {
            ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)

    @BeforeAll
    fun setUp() {
        assumeTrue(gitAvailable(), "git not on PATH")
        repo = createTempDirectory("gitclient").toFile()
        git("init", "-q")
        File(repo, "a.txt").writeText("one\n")
        File(repo, "b.txt").writeText("keep\n")
        git("add", "-A")
        git("commit", "-q", "-m", "first")
        firstSha = git("rev-parse", "HEAD")
        // Second commit changes a.txt only.
        File(repo, "a.txt").writeText("one\ntwo\n")
        git("add", "-A")
        git("commit", "-q", "-m", "second")
    }

    @AfterAll
    fun tearDown() {
        if (::repo.isInitialized) repo.deleteRecursively()
    }

    @Test
    fun `isRepository is true inside a repo and false outside`() {
        GitClient(repo).isRepository() shouldBe true
        val nonRepo = createTempDirectory("notgit").toFile()
        try {
            GitClient(nonRepo).isRepository() shouldBe false
        } finally {
            nonRepo.deleteRecursively()
        }
    }

    @Test
    fun `resolve returns a sha for a valid ref and throws for an unknown one`() {
        GitClient(repo).resolve("HEAD").length shouldBe 40
        runCatching { GitClient(repo).resolve("no-such-ref") }.isFailure shouldBe true
    }

    @Test
    fun `a worktree can be added and removed`() {
        val client = GitClient(repo)
        val wt = File(repo.parentFile, repo.name + "-wt")
        try {
            client.addWorktree(wt, firstSha)
            File(wt, "a.txt").readText() shouldBe "one\n" // the first-commit content
        } finally {
            client.removeWorktree(wt)
            wt.deleteRecursively()
        }
    }

    @Test
    fun `isClean reflects the working tree state`() {
        val client = GitClient(repo)
        client.isClean() shouldBe true
        File(repo, "dirty.txt").writeText("x")
        try {
            client.isClean() shouldBe false
        } finally {
            File(repo, "dirty.txt").delete()
        }
    }

    @Test
    fun `adding a worktree for an unresolved ref throws`() {
        val wt = File(repo.parentFile, repo.name + "-badwt")
        try {
            runCatching { GitClient(repo).addWorktree(wt, "no-such-ref") }.isFailure shouldBe true
        } finally {
            wt.deleteRecursively()
        }
    }

    @Test
    fun `removing a non-existent worktree does not throw`() {
        // removeWorktree ignores git's exit code, so cleanup stays safe even if the worktree is already gone.
        GitClient(repo).removeWorktree(File(repo.parentFile, "never-existed-wt"))
    }

    @Test
    fun `changedLineRangesSince surfaces an unresolved ref as a GitRefException`() {
        // The ref is resolved first; an unknown ref must throw (→ exit 65), not return an empty map.
        shouldThrow<GitRefException> { GitClient(repo).changedLineRangesSince("no-such-ref") }
    }

    @Test
    fun `a git invocation that exceeds the timeout is forcibly killed and treated as failure`() {
        // Inject a process that outlives the 1s timeout: the deadlock guard must destroy it and map the
        // run to the non-zero TIMED_OUT_CODE, which resolve() surfaces as a GitRefException (→ exit 65).
        val hanging = GitClient(repo, timeoutSeconds = 1, spawn = { ProcessBuilder("sleep", "30").start() })
        shouldThrow<GitRefException> { hanging.resolve("HEAD") }
    }

    @Test
    fun `topLevel resolves the repository root and is null outside a repo`() {
        GitClient(repo).topLevel()?.canonicalFile shouldBe repo.canonicalFile
        val nonRepo = createTempDirectory("notgit").toFile()
        try {
            GitClient(nonRepo).topLevel() shouldBe null
        } finally {
            nonRepo.deleteRecursively()
        }
    }

    @Test
    fun `changedLineRangesSince keys hunk ranges by file in working-tree coordinates`() {
        val src = File(repo, "Lines.kt")
        src.writeText((1..9).joinToString("\n", postfix = "\n") { "// line $it" })
        git("add", "-A")
        git("commit", "-q", "-m", "lines")
        val base = git("rev-parse", "HEAD")
        // Touch exactly line 5 in the working tree (uncommitted — the loop's mid-edit state).
        src.writeText((1..9).joinToString("\n", postfix = "\n") { if (it == 5) "// edited $it" else "// line $it" })
        try {
            val ranges = GitClient(repo).changedLineRangesSince(base)
            ranges.keys shouldBe setOf("Lines.kt")
            ranges.getValue("Lines.kt") shouldBe listOf(5..5)
        } finally {
            git("checkout", "-q", "--", "Lines.kt")
        }
    }

    @Test
    fun `changedLineRangesSince keys paths relative to a subdirectory work tree`() {
        // The analyzer's paths are projectRoot-relative; when the project is a repo SUBDIRECTORY the
        // git output must match that base (--relative), or every violation silently drops out of --since.
        val sub = File(repo, "modA").apply { mkdirs() }
        val src = File(sub, "Sub.kt")
        src.writeText("// a\n// b\n")
        git("add", "-A")
        git("commit", "-q", "-m", "sub")
        val base = git("rev-parse", "HEAD")
        src.writeText("// a\n// b changed\n")
        try {
            val ranges = GitClient(sub).changedLineRangesSince(base)
            ranges.keys shouldBe setOf("Sub.kt") // NOT modA/Sub.kt
            ranges.getValue("Sub.kt") shouldBe listOf(2..2)
        } finally {
            git("checkout", "-q", "--", "modA/Sub.kt")
        }
    }

    @Test
    fun `a pure rename carries no hunks and surfaces nothing`() {
        val src = File(repo, "Renamed.kt")
        src.writeText("// stable content\n".repeat(20))
        git("add", "-A")
        git("commit", "-q", "-m", "pre-rename")
        val base = git("rev-parse", "HEAD")
        git("mv", "Renamed.kt", "Renamed2.kt")
        try {
            // -M detects the 100% rename → a hunk-less entry → no ranges → no scope re-surfaces.
            GitClient(repo).changedLineRangesSince(base).keys shouldBe emptySet<String>()
        } finally {
            git("mv", "Renamed2.kt", "Renamed.kt")
            git("reset", "-q", "HEAD", "--", ".")
        }
    }
}

/** Pure hunk-grammar coverage for [parseUnifiedZeroRanges] — no repository required. */
class UnifiedZeroRangesTest {
    @Test
    fun `parses additions, multi-line hunks, and deletion-only hunks`() {
        val diff =
            """
            diff --git a/src/A.kt b/src/A.kt
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -3,0 +4,2 @@ fun a()
            +new line 4
            +new line 5
            @@ -10 +12 @@ fun b()
            -old
            +new
            @@ -20,2 +21,0 @@ fun c()
            -gone
            -gone too
            """.trimIndent()
        parseUnifiedZeroRanges(diff) shouldBe mapOf("src/A.kt" to listOf(4..5, 12..12, 21..21))
    }

    @Test
    fun `a deletion at the top of the file marks line one`() {
        val diff = "+++ b/T.kt\n@@ -1,3 +0,0 @@\n-a\n-b\n-c"
        parseUnifiedZeroRanges(diff) shouldBe mapOf("T.kt" to listOf(1..1))
    }

    @Test
    fun `a deleted file (dev-null new side) yields no ranges`() {
        val diff = "--- a/Gone.kt\n+++ /dev/null\n@@ -1,3 +0,0 @@\n-a\n-b\n-c"
        parseUnifiedZeroRanges(diff) shouldBe emptyMap<String, List<IntRange>>()
    }

    @Test
    fun `two files keep their hunks separate`() {
        val diff = "+++ b/A.kt\n@@ -1 +1 @@\n+++ b/B.kt\n@@ -2 +3,2 @@"
        parseUnifiedZeroRanges(diff) shouldBe mapOf("A.kt" to listOf(1..1), "B.kt" to listOf(3..4))
    }

    @Test
    fun `a malformed hunk header is skipped, not fatal`() {
        val diff = "+++ b/A.kt\n@@ garbage @@\n@@ -1 +7 @@"
        parseUnifiedZeroRanges(diff) shouldBe mapOf("A.kt" to listOf(7..7))
    }
}
