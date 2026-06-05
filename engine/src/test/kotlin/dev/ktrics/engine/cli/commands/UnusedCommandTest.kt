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
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/** `ktrics unused` end-to-end: reporting, filtering, and the `--apply` excision safety gates. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnusedCommandTest {
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
        root: File,
    ): Pair<Int, CapturingSink> {
        val sink = CapturingSink()
        val code = UnusedCommand.run(CommandContext(args.toList(), root, emptyMap(), sink, cwd = root))
        return code to sink
    }

    private fun gitAvailable(): Boolean =
        runCatching {
            ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)

    private fun git(
        dir: File,
        vararg args: String,
    ) {
        ProcessBuilder(listOf("git", "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false", *args))
            .directory(dir).redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS)
    }

    @AfterAll
    fun tearDown() = WarmIndexCache.clear()

    @Test
    fun `console report lists unreachable public symbols`() {
        val (code, sink) = run("--reporter", "console", root = projectRoot)
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "unreachable public symbol"
    }

    @Test
    fun `the ai report carries the contractual header`() {
        val (code, sink) = run("--reporter", "ai", root = projectRoot)
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "# ktrics unused-report v1"
    }

    @Test
    fun `an unsupported reporter is a usage error`() {
        val (code, sink) = run("--reporter", "json", root = projectRoot)
        code shouldBe Exit.USAGE
        sink.err.toString() shouldContain "console|ai"
    }

    @Test
    fun `--filter narrows the report to the named kinds`() {
        // Filtering to a kind that cannot match leaves an empty list (console still prints the summary).
        val (code, sink) = run("--filter", "no-such-kind", "--reporter", "ai", root = projectRoot)
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "unused: []"
    }

    @Test
    fun `--apply excises a top-level orphan on a clean resolved tree`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val repo = createTempDirectory("unused-apply").toFile()
        try {
            val src = File(repo, "app.kt")
            src.writeText(
                """
                package sample

                fun main() {
                    used()
                }

                fun used() {
                    println("used")
                }

                fun orphan() {
                    println("never called")
                }
                """.trimIndent() + "\n",
            )
            git(repo, "init", "-q")
            git(repo, "add", "-A")
            git(repo, "commit", "-q", "-m", "init")

            val (code, sink) = run("--apply", root = repo)
            code shouldBe Exit.OK
            sink.out.toString() shouldContain "removed"
            // The orphan is gone; the reachable declarations remain.
            val after = src.readText()
            after.contains("fun orphan()") shouldBe false
            after.contains("fun used()") shouldBe true
        } finally {
            WarmIndexCache.clear()
            repo.deleteRecursively()
        }
    }

    @Test
    fun `--include-tests widens reporting into test trees`() {
        val tmp = createTempDirectory("unused-incltests").toFile()
        try {
            File(tmp, "src/test/kotlin").mkdirs()
            File(tmp, "src/test/kotlin/HelperTest.kt").writeText("package t\n\nfun testOrphan() {}\n")
            File(tmp, "src/main/kotlin").mkdirs()
            File(tmp, "src/main/kotlin/M.kt").writeText("package m\n\nfun keep() {}\n")
            // By default a declaration in a test tree is excluded from the report...
            run("--reporter", "ai", root = tmp).second.out.toString().contains("testOrphan") shouldBe false
            // ...but --include-tests widens the sweep so the test-tree orphan surfaces.
            run("--include-tests", "--reporter", "ai", root = tmp).second.out.toString() shouldContain "testOrphan"
        } finally {
            WarmIndexCache.clear()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `--filter keeps the matching declaration kind, unlike a non-matching one`() {
        // The existing test pins the no-match→empty direction; this pins that a matching kind is KEPT.
        // testdata/analyze's unreachable symbols are the methods of the Tangled class (kind "method").
        val (code, sink) = run("--filter", "method", "--reporter", "ai", root = projectRoot)
        code shouldBe Exit.OK
        val out = sink.out.toString()
        out shouldContain "kind: method"
        out.contains("unused: []") shouldBe false
    }

    @Test
    fun `a positional target is resolved against the project root`() {
        // Passing a positional path exercises resolveTarget; "." normalises back to the project root.
        val (code, sink) = run(".", "--reporter", "console", root = projectRoot)
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "unreachable public symbol"
    }

    @Test
    fun `--apply removes multiple top-level orphans bottom-up and keeps the reachable code`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val repo = createTempDirectory("unused-multi").toFile()
        try {
            val src = File(repo, "app.kt")
            // Two orphans straddling a reachable function — removing both exercises the bottom-up line math.
            src.writeText(
                "package sample\n\nfun main() {\n    used()\n}\n\n" +
                    "fun orphanA() {\n    println(\"a\")\n}\n\n" +
                    "fun used() {\n    println(\"u\")\n}\n\n" +
                    "fun orphanB() {\n    println(\"b\")\n}\n",
            )
            git(repo, "init", "-q")
            git(repo, "add", "-A")
            git(repo, "commit", "-q", "-m", "init")

            run("--apply", root = repo).first shouldBe Exit.OK
            val after = src.readText()
            after.contains("fun orphanA()") shouldBe false
            after.contains("fun orphanB()") shouldBe false
            after.contains("fun used()") shouldBe true
            after.contains("fun main()") shouldBe true
        } finally {
            WarmIndexCache.clear()
            repo.deleteRecursively()
        }
    }

    @Test
    fun `--apply preserves CRLF line endings when excising`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val repo = createTempDirectory("unused-crlf").toFile()
        try {
            val src = File(repo, "app.kt")
            // A CRLF file: the excision must rejoin with \r\n, not silently normalise to LF.
            src.writeText("package sample\r\n\r\nfun main() {\r\n    used()\r\n}\r\n\r\nfun used() {}\r\n\r\nfun orphan() {}\r\n")
            git(repo, "init", "-q")
            git(repo, "add", "-A")
            git(repo, "commit", "-q", "-m", "init")

            run("--apply", root = repo).first shouldBe Exit.OK
            val after = src.readText()
            after.contains("fun orphan()") shouldBe false
            after.contains("\r\n") shouldBe true // CRLF preserved
            after.contains("fun used()") shouldBe true
        } finally {
            WarmIndexCache.clear()
            repo.deleteRecursively()
        }
    }

    @Test
    fun `--apply reports nothing to delete when there are no top-level orphans`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val repo = createTempDirectory("unused-noorphan").toFile()
        try {
            // Everything is reachable from main → a resolved tree with zero deletable orphans.
            File(repo, "app.kt").writeText("package sample\n\nfun main() {\n    used()\n}\n\nfun used() {\n    println(\"x\")\n}\n")
            git(repo, "init", "-q")
            git(repo, "add", "-A")
            git(repo, "commit", "-q", "-m", "init")

            val (code, sink) = run("--apply", root = repo)
            code shouldBe Exit.OK
            sink.out.toString() shouldContain "nothing to delete"
        } finally {
            WarmIndexCache.clear()
            repo.deleteRecursively()
        }
    }

    @Test
    fun `--apply with --force excises even on a dirty git tree`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val repo = createTempDirectory("unused-force").toFile()
        try {
            val src = File(repo, "app.kt")
            src.writeText("package sample\n\nfun main() {\n    used()\n}\n\nfun used() {}\n\nfun orphan() {}\n")
            git(repo, "init", "-q")
            git(repo, "add", "-A")
            git(repo, "commit", "-q", "-m", "init")
            File(repo, "extra.txt").writeText("uncommitted") // dirty tree

            run("--apply", "--force", root = repo).first shouldBe Exit.OK
            src.readText().contains("fun orphan()") shouldBe false // --force overrode the clean-tree gate
        } finally {
            WarmIndexCache.clear()
            repo.deleteRecursively()
        }
    }

    @Test
    fun `--apply is refused on a dirty git tree without --force`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val repo = createTempDirectory("unused-dirty").toFile()
        try {
            File(repo, "app.kt").writeText("package sample\n\nfun main() {\n    used()\n}\n\nfun used() {}\n\nfun orphan() {}\n")
            git(repo, "init", "-q")
            git(repo, "add", "-A")
            git(repo, "commit", "-q", "-m", "init")
            // Leave an uncommitted change so safety gate 2 (clean-tree) trips.
            File(repo, "extra.txt").writeText("uncommitted")

            val (code, sink) = run("--apply", root = repo)
            code shouldBe Exit.BAD_INPUT
            sink.err.toString() shouldContain "uncommitted changes"
        } finally {
            WarmIndexCache.clear()
            repo.deleteRecursively()
        }
    }

    @Test
    fun `--apply is refused when reachability is name-based`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val repo = createTempDirectory("unused-namebased").toFile()
        try {
            File(repo, "ktrics.yaml").writeText("ktrics:\n  resolution: name-based\n")
            File(repo, "app.kt").writeText("package sample\n\nfun main() {}\n\nfun orphan() {}\n")
            git(repo, "init", "-q")
            git(repo, "add", "-A")
            git(repo, "commit", "-q", "-m", "init")

            val (code, sink) = run("--apply", root = repo)
            code shouldBe Exit.BAD_INPUT
            sink.err.toString() shouldContain "name-based"
        } finally {
            WarmIndexCache.clear()
            repo.deleteRecursively()
        }
    }

    // --- whole-node deletion guard: a span must own its physical lines to be auto-deletable ---

    private fun symbolAt(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
    ): dev.ktrics.unused.UnusedSymbol =
        dev.ktrics.unused.UnusedSymbol(
            key = "pkg.dead",
            displayName = "dead",
            kind = "property",
            visibility = dev.ktrics.ir.Visibility.PUBLIC,
            file = "X.kt",
            span = dev.ktrics.ir.Span("X.kt", startLine, startColumn, endLine, endColumn, 0, 0),
            lang = dev.ktrics.ir.Lang.KOTLIN,
            topLevel = true,
        )

    @Test
    fun `a declaration alone on its lines is deletable`() {
        val lines = listOf("package pkg", "", "val dead = 2")
        UnusedCommand.ownsItsLines(lines, symbolAt(3, 1, 3, 13)) shouldBe true
    }

    @Test
    fun `a declaration sharing its line with a live sibling is NOT deletable`() {
        // `val live = 1; val dead = 2` — line-granular removal of `dead` would take `live` with it.
        val lines = listOf("package pkg", "", "val live = 1; val dead = 2")
        UnusedCommand.ownsItsLines(lines, symbolAt(3, 15, 3, 27)) shouldBe false
        // And the leading sibling is just as coupled: text trails ITS last line.
        UnusedCommand.ownsItsLines(lines, symbolAt(3, 1, 3, 13)) shouldBe false
    }

    @Test
    fun `a multi-line declaration ending at column one owns the previous line`() {
        // PSI ranges ending at column 1 stop at the START of endLine (the removeSpans convention).
        val lines = listOf("fun dead() {", "    return", "}", "val next = 1")
        UnusedCommand.ownsItsLines(lines, symbolAt(1, 1, 4, 1)) shouldBe true
    }
}
