package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.WarmIndexCache
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandSink
import dev.ktrics.engine.cli.Exit
import dev.ktrics.testsession.repoRoot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.io.path.createTempDirectory

/** `ktrics inspect` walks the resolved call graph around a symbol (doc/calibration.md). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InspectCommandTest {
    private val projectRoot = repoRoot().resolve("testdata/spike")

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

    private fun run(vararg args: String): Pair<Int, CapturingSink> {
        val sink = CapturingSink()
        val code = InspectCommand.run(CommandContext(args.toList(), projectRoot, emptyMap(), sink, cwd = projectRoot))
        return code to sink
    }

    @AfterAll
    fun tearDown() = WarmIndexCache.clear()

    @Test
    fun `ai report walks the subgraph around a known symbol`() {
        val (code, sink) = run("ping")
        code shouldBe Exit.OK
        val out = sink.out.toString()
        out shouldContain "# ktrics inspect-report v1"
        out shouldContain "query: ping"
        out shouldContain "CoreApi.ping" // the anchor scope
    }

    @Test
    fun `json report is emitted for --reporter json`() {
        val (code, sink) = run("ping", "--reporter", "json")
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "\"query\": \"ping\""
    }

    @Test
    fun `a missing symbol argument is a usage error`() {
        val (code, sink) = run()
        code shouldBe Exit.USAGE
        sink.err.toString() shouldContain "inspect <symbol>"
    }

    @Test
    fun `an unsupported reporter is a usage error`() {
        val (code, sink) = run("ping", "--reporter", "md")
        code shouldBe Exit.USAGE
        sink.err.toString() shouldContain "ai|json"
    }

    @Test
    fun `a non-positive depth is a usage error`() {
        val (code, sink) = run("ping", "--depth", "0")
        code shouldBe Exit.USAGE
        sink.err.toString() shouldContain "positive integer"
    }

    @Test
    fun `an explicit positive depth is accepted`() {
        // The existing depth test only covers the rejection path; this drives the accepted-value return.
        run("ping", "--depth", "2").first shouldBe Exit.OK
    }

    @Test
    fun `a bad direction is a usage error`() {
        val (code, sink) = run("ping", "--direction", "sideways")
        code shouldBe Exit.USAGE
        sink.err.toString() shouldContain "up|down|both"
    }

    @Test
    fun `the direction flag changes which side of the graph is walked`() {
        val up = run("ping", "--direction", "up")
        val down = run("ping", "--direction", "down")
        up.first shouldBe Exit.OK
        down.first shouldBe Exit.OK
        // up surfaces callers, down surfaces callees → the two neighbourhoods are not the same text.
        up.second.out.toString() shouldNotBe down.second.out.toString()
    }

    @Test
    fun `an unknown symbol yields an empty matches block`() {
        val (code, sink) = run("definitelyNotASymbol")
        code shouldBe Exit.OK
        sink.out.toString() shouldContain "matches: []"
    }

    @Test
    fun `--output writes the inspect report to a file`() {
        val tmp = createTempDirectory("inspect-out").toFile()
        try {
            val out = File(tmp, "inspect.txt")
            val (code, sink) = run("ping", "--output", out.absolutePath)
            code shouldBe Exit.OK
            out.isFile shouldBe true
            sink.err.toString() shouldContain "wrote inspect report"
        } finally {
            tmp.deleteRecursively()
        }
    }
}
