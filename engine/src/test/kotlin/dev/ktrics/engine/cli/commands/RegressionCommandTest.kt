package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.WarmIndexCache
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandSink
import dev.ktrics.engine.cli.Exit
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/** `ktrics regression` diffs two refs analyzed in detached worktrees. Needs git on PATH. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegressionCommandTest {
    private lateinit var repo: File
    private lateinit var firstSha: String
    private lateinit var secondSha: String
    private lateinit var cosmeticSha: String

    private class CapturingSink : CommandSink {
        val out = StringBuilder()
        val err = StringBuilder()

        override fun out(text: String) {
            out.append(text)
        }

        override fun err(text: String) {
            err.append(text)
        }
    }

    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val p =
            ProcessBuilder(
                listOf("git", "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false", *args),
            ).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        return out.trim()
    }

    private fun gitAvailable(): Boolean =
        runCatching {
            ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)

    private fun run(
        root: File,
        vararg args: String,
    ): Pair<Int, CapturingSink> {
        val sink = CapturingSink()
        val code = RegressionCommand.run(CommandContext(args.toList(), root, emptyMap(), sink, cwd = root))
        return code to sink
    }

    @BeforeAll
    fun setUp() {
        assumeTrue(gitAvailable(), "git not on PATH")
        repo = createTempDirectory("regression").toFile()
        val src = File(repo, "Sample.kt")
        git(repo, "init", "-q")
        // ref1: a simple function (cyclomatic 1).
        src.writeText("package sample\n\nfun f(x: Int): Int = x + 1\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "simple")
        firstSha = git(repo, "rev-parse", "HEAD")
        // ref2: the same scope, now branchy (cyclomatic > 1) → a regression for a lower-is-better lens.
        src.writeText(
            "package sample\n\nfun f(x: Int): Int {\n" +
                "    if (x > 0) return 1\n" +
                "    if (x < 0) return 2\n" +
                "    return 3\n}\n",
        )
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "branchy")
        secondSha = git(repo, "rev-parse", "HEAD")
        // ref3: f restored to its simple form, but FOUR new tiny-complexity helpers (cc 1) bloat the line
        // count without reducing complexity — the cosmetic-refactor heuristic should fire.
        src.writeText(cosmeticSource())
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "cosmetic")
        cosmeticSha = git(repo, "rev-parse", "HEAD")
    }

    private fun cosmeticSource(): String {
        val helper = { n: Int ->
            "fun h$n(): Int {\n    val a = 1\n    val b = 2\n    val c = 3\n    val d = 4\n    return a + b + c + d\n}\n"
        }
        return "package sample\n\nfun f(x: Int): Int = x + 1\n\n" + (1..4).joinToString("\n") { helper(it) }
    }

    @AfterAll
    fun tearDown() {
        WarmIndexCache.clear()
        if (::repo.isInitialized) repo.deleteRecursively()
    }

    @Test
    fun `console diff reports the cyclomatic regression between the two refs`() {
        val (code, sink) = run(repo, "--before", firstSha, "--after", secondSha)
        code shouldBe Exit.OK
        val out = sink.out.toString()
        out shouldContain "ktrics regression:"
        // The branchier body regresses the lower-is-better function lenses, cyclomatic among them.
        out shouldContain "cyclomatic-complexity"
    }

    @Test
    fun `the console report flags a suspected cosmetic refactor`() {
        val (code, sink) = run(repo, "--before", firstSha, "--after", cosmeticSha)
        code shouldBe Exit.OK
        // Four tiny helpers added, lines way up, complexity unchanged → the cosmetic warning line renders.
        sink.out.toString() shouldContain "cosmetic-refactor suspected"
    }

    @Test
    fun `the ai report carries the contractual header and summary`() {
        val (code, sink) = run(repo, "--before", firstSha, "--after", secondSha, "--reporter", "ai")
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "# ktrics regression-report v1"
    }

    @Test
    fun `the json report encodes the summary`() {
        val (code, sink) = run(repo, "--before", firstSha, "--after", secondSha, "--reporter", "json")
        code shouldBe Exit.OK
        val out = sink.out.toString()
        out shouldContain "\"change\": \"regressed\""
        out shouldContain "cyclomatic-complexity"
    }

    @Test
    fun `--metric narrows the diff to the named metric`() {
        val (code, _) = run(repo, "--before", firstSha, "--after", secondSha, "--metric", "cyclomatic-complexity")
        code shouldBe Exit.OK
    }

    @Test
    fun `--output writes the regression report to a file and notes it on stderr`() {
        val tmp = createTempDirectory("regression-out").toFile()
        try {
            val out = File(tmp, "regression.txt")
            val (code, sink) = run(repo, "--before", firstSha, "--after", secondSha, "--output", out.absolutePath)
            code shouldBe Exit.OK
            out.isFile shouldBe true
            out.readText() shouldContain "cyclomatic-complexity"
            sink.err.toString() shouldContain "wrote regression report"
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `comparing a ref against itself yields an empty ai changes block`() {
        // before == after → no scope changes → the ai renderer emits the empty-changes sentinel.
        val (code, sink) = run(repo, "--before", firstSha, "--after", firstSha, "--reporter", "ai")
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "changes: []"
    }

    @Test
    fun `a missing --before is a usage error`() {
        val (code, sink) = run(repo, "--after", secondSha)
        code shouldBe Exit.USAGE
        sink.err.toString() shouldContain "usage"
    }

    @Test
    fun `an unsupported reporter is a usage error`() {
        val (code, sink) = run(repo, "--before", firstSha, "--after", secondSha, "--reporter", "sarif")
        code shouldBe Exit.USAGE
    }

    @Test
    fun `an unresolved ref is a bad-input error`() {
        val (code, sink) = run(repo, "--before", firstSha, "--after", "no-such-ref")
        code shouldBe Exit.BAD_INPUT
        sink.err.toString() shouldContain "git ref"
    }

    @Test
    fun `running outside a git repository is a bad-input error`() {
        val notRepo = createTempDirectory("not-a-repo").toFile()
        try {
            val (code, sink) = run(notRepo, "--before", "a", "--after", "b")
            code shouldBe Exit.BAD_INPUT
            sink.err.toString() shouldContain "not a git repository"
        } finally {
            notRepo.deleteRecursively()
        }
    }
}
