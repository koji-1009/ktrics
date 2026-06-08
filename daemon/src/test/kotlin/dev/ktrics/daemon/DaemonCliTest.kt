package dev.ktrics.daemon

import dev.ktrics.engine.cli.CommandSink
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/** The daemon's one-shot logic via [DaemonCli] — help/version/unknown/dispatch, with no socket. */
class DaemonCliTest {
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

    private fun run(vararg argv: String): Pair<Int, CapturingSink> {
        val sink = CapturingSink()
        val code = DaemonCli.runForeground(argv.toList(), sink = sink, cwd = File("."))
        return code to sink
    }

    @Test
    fun `no args prints usage and exits zero`() {
        val (code, sink) = run()
        code shouldBe 0
        sink.out.toString() shouldContain "run with --serve"
    }

    @Test
    fun `--help prints usage`() {
        run("--help").second.out.toString() shouldContain "one-shot"
    }

    @Test
    fun `--version prints the daemon version`() {
        val (code, sink) = run("--version")
        code shouldBe 0
        sink.out.toString() shouldContain "daemon one-shot"
    }

    @Test
    fun `a supported command is dispatched through the cli`() {
        val (code, sink) = run("rules")
        code shouldBe 0
        sink.out.toString() shouldContain "metric catalogue"
    }

    @Test
    fun `an unknown command is a usage error`() {
        val (code, sink) = run("frobnicate")
        code shouldBe 64
        sink.err.toString() shouldContain "unknown command 'frobnicate'"
    }

    @Test
    fun `optionValue reads a flag value and is null when absent`() {
        DaemonCli.optionValue(listOf("--root", "/p", "analyze"), "--root") shouldBe "/p"
        DaemonCli.optionValue(listOf("analyze"), "--root").shouldBeNull()
    }

    @Test
    fun `findProjectRoot walks up to the nearest ktrics-yaml ancestor`() {
        val root = kotlin.io.path.createTempDirectory("ktrics-root").toFile()
        try {
            File(root, "ktrics.yaml").writeText("ktrics: {}\n")
            val sub = File(root, "a/b/c").apply { mkdirs() }
            // From a deep subdirectory we resolve back up to the project root holding the config.
            DaemonCli.findProjectRoot(sub) shouldBe root.absoluteFile
            // With no config anywhere up the tree, the starting directory itself is the root.
            val orphan = kotlin.io.path.createTempDirectory("ktrics-orphan").toFile()
            try {
                DaemonCli.findProjectRoot(orphan) shouldBe orphan.absoluteFile.normalize()
            } finally {
                orphan.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a --root option after the command overrides the working directory`() {
        // `rules` ignores the root but the option still drives the root-resolution branch of runForeground.
        val (code, sink) = run("rules", "--root", File(".").absolutePath)
        code shouldBe 0
        sink.out.toString() shouldContain "metric catalogue"
    }

    @Test
    fun `runForeground uses its default sink and cwd when only argv is given`() {
        val out = java.io.ByteArrayOutputStream()
        val orig = System.out
        try {
            System.setOut(java.io.PrintStream(out))
            DaemonCli.runForeground(listOf("--version")) // defaults for sink (StreamSink), cwd, cli
        } finally {
            System.setOut(orig)
        }
        out.toString() shouldContain "daemon one-shot"
    }

    @Test
    fun `StreamSink writes out to stdout and err to stderr`() {
        val outBuf = java.io.ByteArrayOutputStream()
        val errBuf = java.io.ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        try {
            System.setOut(java.io.PrintStream(outBuf))
            System.setErr(java.io.PrintStream(errBuf))
            val sink = StreamSink()
            sink.out("to-stdout")
            sink.err("to-stderr")
            System.out.flush()
            System.err.flush()
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
        outBuf.toString() shouldContain "to-stdout"
        errBuf.toString() shouldContain "to-stderr"
    }
}
