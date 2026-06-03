package dev.ktrics.vcs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
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
 * real `git diff`/`show`/`worktree` round-trips.
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
    fun `changedFilesSince lists files changed since a ref`() {
        // Between the first commit and the working tree, only a.txt changed.
        val changed = GitClient(repo).changedFilesSince(firstSha)
        changed shouldContain "a.txt"
        changed.contains("b.txt") shouldBe false
    }

    @Test
    fun `fileAt returns the contents at a ref and null for a missing path`() {
        val client = GitClient(repo)
        client.fileAt(firstSha, "a.txt") shouldBe "one\n"
        client.fileAt(firstSha, "ghost.txt").shouldBeNull()
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
    fun `changedFilesBetween lists files that differ across two refs`() {
        // a.txt changed between the first and second commits; b.txt did not.
        val changed = GitClient(repo).changedFilesBetween(firstSha, "HEAD")
        changed shouldContain "a.txt"
        changed.contains("b.txt") shouldBe false
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
    fun `changedFilesSince surfaces an unresolved ref as a GitRefException`() {
        // changedFilesSince resolves the ref first; an unknown ref must throw (→ exit 65), not return [].
        shouldThrow<GitRefException> { GitClient(repo).changedFilesSince("no-such-ref") }
    }

    @Test
    fun `a git invocation that exceeds the timeout is forcibly killed and treated as failure`() {
        // Inject a process that outlives the 1s timeout: the deadlock guard must destroy it and map the
        // run to the non-zero TIMED_OUT_CODE, which resolve() surfaces as a GitRefException (→ exit 65).
        val hanging = GitClient(repo, timeoutSeconds = 1, spawn = { ProcessBuilder("sleep", "30").start() })
        shouldThrow<GitRefException> { hanging.resolve("HEAD") }
    }
}
