package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.WarmIndexCache
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandSink
import dev.ktrics.engine.cli.Exit
import dev.ktrics.testsession.repoRoot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.io.path.createTempDirectory

/** `ktrics analyze` end-to-end over a real session + fixture, across reporters and flags. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyzeCommandTest {
    private val projectRoot = repoRoot().resolve("testdata/analyze")

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

    private fun run(
        vararg args: String,
        root: File = projectRoot,
    ): Pair<Int, CapturingSink> {
        val sink = CapturingSink()
        val code = AnalyzeCommand.run(CommandContext(args.toList(), root, emptyMap(), sink, cwd = root))
        return code to sink
    }

    @AfterAll
    fun tearDown() = WarmIndexCache.clear()

    @Test
    fun `console run reports the tangled violation`() {
        val (code, sink) = run()
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "cyclomatic-complexity"
    }

    @Test
    fun `the ai reporter emits the contractual header`() {
        val (code, sink) = run("--reporter", "ai")
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "# ktrics ai-report v1"
    }

    @Test
    fun `the json reporter round-trips through the report parser`() {
        val (_, sink) = run("--reporter", "json")
        val parsed = dev.ktrics.report.JsonReporter.parse(sink.out.toString())
        parsed.violations.any { it.metricId == "cyclomatic-complexity" } shouldBe true
    }

    @Test
    fun `the sarif reporter emits the schema version`() {
        val (_, sink) = run("--reporter", "sarif")
        sink.out.toString() shouldContain "2.1.0"
    }

    @Test
    fun `an unknown reporter is a usage error`() {
        val (code, sink) = run("--reporter", "xml")
        code shouldBe Exit.USAGE
        sink.err.toString() shouldContain "unknown reporter"
    }

    @Test
    fun `a missing coverage file is a bad-input error`() {
        val (code, sink) = run("--coverage", "nope.xml")
        code shouldBe Exit.BAD_INPUT
        sink.err.toString() shouldContain "coverage file not found"
    }

    @Test
    fun `fatal-warnings turns present violations into a non-zero exit`() {
        val (code, _) = run("--fatal-warnings")
        code shouldBe Exit.VIOLATIONS
    }

    @Test
    fun `--output writes the report to a file and notes it on stderr`() {
        val tmp = createTempDirectory("analyze-out").toFile()
        try {
            val out = File(tmp, "report.json")
            val (code, sink) = run("--reporter", "json", "--output", out.absolutePath)
            code shouldBe Exit.OK
            out.isFile shouldBe true
            out.readText() shouldContain "cyclomatic-complexity"
            sink.err.toString() shouldContain "wrote json report"
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `--no-auto-explain drops the rationale from the ai report`() {
        val (_, sink) = run("--reporter", "ai", "--no-auto-explain")
        sink.out.toString().contains("rationale:") shouldBe false
    }

    @Test
    fun `--limit caps the ai violations block`() {
        val (_, sink) = run("--reporter", "ai", "--limit", "0")
        // With a limit of 0 every violation is truncated away.
        sink.out.toString() shouldContain "violations: []"
    }

    @Test
    fun `--since on a non-git directory is a bad-input error`() {
        val tmp = createTempDirectory("analyze-since").toFile()
        try {
            File(tmp, "src/main/kotlin").mkdirs()
            File(tmp, "src/main/kotlin/X.kt").writeText("package x\n\nfun f(): Int = 1\n")
            // The temp dir is not a git repo, so resolving `--since HEAD` fails → exit 65.
            run("--since", "HEAD", root = tmp).first shouldBe Exit.BAD_INPUT
        } finally {
            WarmIndexCache.clear()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `--snapshot baseline writes a baseline and notes it on stderr`() {
        val tmp = createTempDirectory("analyze-snap").toFile()
        try {
            File(tmp, "src/main/kotlin").mkdirs()
            File(tmp, "src/main/kotlin/X.kt").writeText("package x\n\nfun f(): Int = 1\n")
            val (code, sink) = run("--snapshot", "baseline", root = tmp)
            code shouldBe Exit.OK
            File(tmp, ".ktrics/snapshot.json").isFile shouldBe true
            sink.err.toString() shouldContain "baseline saved"
        } finally {
            WarmIndexCache.clear()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `--strict-dismiss ignores a sidecar dismissal so the violation resurfaces`() {
        val tmp = createTempDirectory("analyze-strict").toFile()
        try {
            File(tmp, "src/main/kotlin").mkdirs()
            File(tmp, "src/main/kotlin/X.kt").writeText(
                "package x\n\nfun f(n: Int): Int {\n" +
                    (1..15).joinToString("\n") { "    if (n == $it) return $it" } +
                    "\n    return 0\n}\n",
            )
            // A sidecar (metric + file selector — no span/position subtlety) dismisses f's cyclomatic
            // violation. Match the violation's own `metric:` line, since other live violations' rationales
            // also mention "cyclomatic".
            File(tmp, "ktrics-dismissals.yaml").writeText(
                "dismissals:\n" +
                    "  - metric: cyclomatic-complexity\n" +
                    "    file: src/main/kotlin/X.kt\n" +
                    "    reason: reviewed in this test, intentional\n",
            )
            // Normal run: the sidecar suppresses it...
            run("--reporter", "ai", root = tmp).second.out.toString().contains("metric: cyclomatic-complexity") shouldBe false
            // ...--strict-dismiss ignores all dismissals, so it comes back.
            run("--reporter", "ai", "--strict-dismiss", root = tmp).second.out.toString() shouldContain "metric: cyclomatic-complexity"
        } finally {
            WarmIndexCache.clear()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a violation past the error threshold is recorded at error severity`() {
        val tmp = createTempDirectory("analyze-error").toFile()
        try {
            File(tmp, "src/main/kotlin").mkdirs()
            File(tmp, "src/main/kotlin/X.kt").writeText(
                "package x\n\nfun f(n: Int): Int {\n" +
                    (1..25).joinToString("\n") { "    if (n == $it) return $it" } +
                    "\n    return 0\n}\n",
            )
            // cyclomatic ~26 > the error threshold (20) → an ERROR-severity result, exercising record's ERROR arm.
            run("--reporter", "ai", root = tmp).second.out.toString() shouldContain "severity: error"
        } finally {
            WarmIndexCache.clear()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a positional target is resolved against the project root`() {
        // Passing "." exercises resolve(); it normalises back to the project root and analyses it.
        val (code, sink) = run(".")
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "cyclomatic-complexity"
    }

    @Test
    fun `a valid --coverage file marks a well-covered complexity violation as justified`() {
        val tmp = createTempDirectory("analyze-cov").toFile()
        try {
            // Cover the REAL violating scope (sample.Tangled.tangled) at 90% branch coverage (>= 0.8).
            val cov = File(tmp, "jacoco.xml")
            cov.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="t"><package name="sample"><class name="sample/Tangled" sourcefilename="Tangled.kt">
                <method name="tangled" desc="(IZZZ)I" line="7"><counter type="BRANCH" missed="1" covered="9"/></method>
                </class></package></report>
                """.trimIndent(),
            )
            val (code, sink) = run("--coverage", cov.absolutePath, "--reporter", "ai")
            code shouldBe Exit.OK
            // The actual effect of --coverage: the well-covered cyclomatic violation is stamped justified.
            sink.out.toString() shouldContain "complexityJustified: true"
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `--since filters out violations in files unchanged since the ref`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val repo = createTempDirectory("analyze-since-ok").toFile()
        try {
            File(repo, "src/main/kotlin").mkdirs()
            // A genuinely violating function (cyclomatic well over the threshold).
            File(repo, "src/main/kotlin/X.kt").writeText(
                "package x\n\nfun f(n: Int): Int {\n" +
                    (1..15).joinToString("\n") { "    if (n == $it) return $it" } +
                    "\n    return 0\n}\n",
            )
            git(repo, "init", "-q")
            git(repo, "add", "-A")
            git(repo, "commit", "-q", "-m", "init")
            // Baseline (no --since): the file really does violate cyclomatic-complexity...
            run("--reporter", "ai", root = repo).second.out.toString() shouldContain "cyclomatic-complexity"
            // ...but on a clean tree nothing changed since HEAD, so --since subtracts every violation.
            val (code, sink) = run("--since", "HEAD", "--reporter", "ai", root = repo)
            code shouldBe Exit.OK
            sink.out.toString() shouldContain "violations: []"
        } finally {
            WarmIndexCache.clear()
            repo.deleteRecursively()
        }
    }

    @Test
    fun `--snapshot cache writes only when absent, then compares without re-saving`() {
        val tmp = createTempDirectory("analyze-cache").toFile()
        try {
            File(tmp, "src/main/kotlin").mkdirs()
            File(tmp, "src/main/kotlin/X.kt").writeText("package x\n\nfun f(): Int = 1\n")
            // First run: the cache file is absent → it is written (but there is nothing to diff against yet).
            val first = run("--snapshot", "cache", root = tmp)
            first.first shouldBe Exit.OK
            File(tmp, ".ktrics/snapshot.json").isFile shouldBe true
            first.second.err.toString() shouldContain "baseline saved"
            // Second run: the cache exists → it is NOT re-saved, but it IS compared against.
            val second = run("--snapshot", "cache", root = tmp)
            second.first shouldBe Exit.OK
            second.second.err.toString() shouldContain "since baseline"
        } finally {
            WarmIndexCache.clear()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `--snapshot baseline run twice compares against the stored baseline`() {
        val tmp = createTempDirectory("analyze-snap2").toFile()
        try {
            File(tmp, "src/main/kotlin").mkdirs()
            File(tmp, "src/main/kotlin/X.kt").writeText("package x\n\nfun f(): Int = 1\n")
            run("--snapshot", "baseline", root = tmp) // first run writes the baseline
            val (code, sink) = run("--snapshot", "baseline", root = tmp) // second run diffs against it
            code shouldBe Exit.OK
            sink.err.toString() shouldContain "since baseline"
        } finally {
            WarmIndexCache.clear()
            tmp.deleteRecursively()
        }
    }

    private fun gitAvailable(): Boolean =
        runCatching {
            ProcessBuilder("git", "--version").start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(false)

    private fun git(
        dir: File,
        vararg args: String,
    ) {
        ProcessBuilder(listOf("git", "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false", *args))
            .directory(dir).redirectErrorStream(true).start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
    }
}
