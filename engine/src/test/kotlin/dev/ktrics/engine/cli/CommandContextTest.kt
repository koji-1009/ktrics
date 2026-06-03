package dev.ktrics.engine.cli

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

class CommandContextTest {
    private class CapturingSink : CommandSink {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        override fun out(text: String) {
            stdout.append(text)
        }

        override fun err(text: String) {
            stderr.append(text)
        }
    }

    private fun ctx(
        vararg args: String,
        sink: CommandSink = CapturingSink(),
    ) = CommandContext(args.toList(), File("."), emptyMap(), sink)

    @Test
    fun `positionals skips a value-option's value and honours the -- terminator`() {
        ctx("--reporter", "ai", "src/", "--", "--weird")
            .positionals() shouldContainExactly listOf("src/", "--weird")
    }

    @Test
    fun `a bare flag does not consume the following positional`() {
        val c = ctx("--strict-dismiss", "target")
        c.positionals() shouldContainExactly listOf("target")
        c.firstPositional() shouldBe "target"
    }

    @Test
    fun `an option's value is never mistaken for the target`() {
        // The classic bug: `analyze --reporter ai src/` must NOT pick `ai` as the positional.
        ctx("--reporter", "ai", "src/").firstPositional() shouldBe "src/"
    }

    @Test
    fun `option reads a value, flag reads presence`() {
        val c = ctx("--depth", "3", "--apply")
        c.option("--depth") shouldBe "3"
        c.option("--missing").shouldBeNull()
        c.flag("--apply") shouldBe true
        c.flag("--nope") shouldBe false
    }

    @Test
    fun `a bare dash is a positional, not an option`() {
        ctx("-").positionals() shouldContainExactly listOf("-")
    }

    @Test
    fun `option reads the inline --name=value form`() {
        val c = ctx("--reporter=json", "src/")
        c.option("--reporter") shouldBe "json"
        // The inline-option token is not a positional; the real target survives.
        c.positionals() shouldContainExactly listOf("src/")
        c.firstPositional() shouldBe "src/"
    }

    @Test
    fun `option keeps an empty inline value distinct from absence`() {
        ctx("--output=").option("--output") shouldBe ""
        ctx().option("--output").shouldBeNull()
    }

    @Test
    fun `a negative-number positional is not mistaken for an inline option`() {
        // `-1` has no `--` prefix, so it is never read as `--name=value`; it stays a positional value.
        ctx("--depth=2", "-1").option("--depth") shouldBe "2"
    }

    @Test
    fun `Cli reports an unknown command as a usage error`() {
        val sink = CapturingSink()
        val code = Cli(emptyMap()).run("nope", ctx(sink = sink))
        code shouldBe Exit.USAGE
        sink.stderr.toString() shouldContain "not available"
    }

    @Test
    @Suppress("TooGenericExceptionThrown") // The test deliberately throws a generic exception to drive the handler's catch-all.
    fun `Cli maps a thrown handler to an internal error`() {
        val sink = CapturingSink()
        val cli = Cli(mapOf("boom" to CommandHandler { throw RuntimeException("kaboom") }))
        val code = cli.run("boom", ctx(sink = sink))
        code shouldBe Exit.INTERNAL
        sink.stderr.toString() shouldContain "internal error"
    }
}
