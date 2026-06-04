package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.cli.Cli
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandSink
import dev.ktrics.engine.cli.Exit
import dev.ktrics.ir.Lang
import dev.ktrics.ir.Span
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Severity
import dev.ktrics.metric.StableId
import dev.ktrics.metric.Violation
import dev.ktrics.report.AnalysisReport
import dev.ktrics.report.FileEntry
import dev.ktrics.report.JsonReporter
import dev.ktrics.report.ReportSummary
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** CLI command handlers that need no live session: doctor, report, rules, manual. */
class CliCommandsTest {
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

    private fun ctx(
        root: File,
        vararg args: String,
        sink: CommandSink,
    ): CommandContext = CommandContext(args.toList(), root, emptyMap(), sink, cwd = root)

    // --- doctor ---

    @Test
    fun `doctor reports a valid config as OK`() {
        val root = createTempDirectory("doctor").toFile()
        try {
            File(root, "ktrics.yaml").writeText("ktrics:\n  metrics:\n    cyclomatic-complexity: { warning: 10, error: 20 }\n")
            val sink = CapturingSink()
            DoctorCommand.run(ctx(root, sink = sink)) shouldBe Exit.OK
            sink.out.toString() shouldContain "configuration is valid"
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `doctor exits BAD_CONFIG on an unknown metric`() {
        val root = createTempDirectory("doctor").toFile()
        try {
            File(root, "ktrics.yaml").writeText("ktrics:\n  metrics:\n    not-a-metric: { warning: 1 }\n")
            val sink = CapturingSink()
            DoctorCommand.run(ctx(root, sink = sink)) shouldBe Exit.BAD_CONFIG
            sink.out.toString() shouldContain "error"
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `doctor renders a warning (not an error) for an unknown unused preset`() {
        val root = createTempDirectory("doctor").toFile()
        try {
            File(root, "ktrics.yaml").writeText("ktrics:\n  unused:\n    presets: [not-a-real-preset]\n")
            val sink = CapturingSink()
            // An unknown preset is forward-compatible → a WARNING, so the exit stays OK but the warn prefix renders.
            DoctorCommand.run(ctx(root, sink = sink)) shouldBe Exit.OK
            sink.out.toString() shouldContain "warn"
            sink.out.toString() shouldContain "unknown unused preset"
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `doctor on a project without a config reports the built-in defaults`() {
        val root = createTempDirectory("doctor").toFile()
        try {
            val sink = CapturingSink()
            DoctorCommand.run(ctx(root, sink = sink)) shouldBe Exit.OK
            sink.out.toString() shouldContain "no ktrics.yaml found"
        } finally {
            root.deleteRecursively()
        }
    }

    // --- report ---

    private fun sampleReportJson(): String {
        val span = Span("src/Foo.kt", 5, 1, 5, 1, 0, 0)
        val v =
            Violation(
                id = StableId.of("src/Foo.kt", "com.x.Foo.bar", "cyclomatic-complexity"),
                metricId = "cyclomatic-complexity", severity = Severity.ERROR, polarity = Polarity.LOWER_IS_BETTER,
                appliesTo = AppliesTo.BOTH, file = "src/Foo.kt", scope = "com.x.Foo.bar", scopeName = "Foo.bar()",
                scopeKind = ScopeKind.FUNCTION, lang = Lang.KOTLIN, value = 30.0, threshold = 20.0, span = span,
                rationale = "r", refactorHints = emptyList(), references = emptyList(),
            )
        val files = listOf(FileEntry("src/Foo.kt", Lang.KOTLIN, 1))
        val report =
            AnalysisReport(
                version = "t",
                root = "/proj",
                summary = ReportSummary.of(listOf(v), files),
                violations = listOf(v),
                files = files,
            )
        return JsonReporter().render(report)
    }

    @Test
    fun `report re-emits a saved JSON report in another format`() {
        val root = createTempDirectory("report").toFile()
        try {
            File(root, "report.json").writeText(sampleReportJson())
            val sink = CapturingSink()
            ReportCommand.run(ctx(root, "report.json", "--reporter", "console", sink = sink)) shouldBe Exit.OK
            sink.out.toString() shouldContain "cyclomatic-complexity"
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `report without an input is a usage error`() {
        val sink = CapturingSink()
        ReportCommand.run(ctx(File("."), sink = sink)) shouldBe Exit.USAGE
        sink.err.toString() shouldContain "usage"
    }

    @Test
    fun `report on a missing file is a bad-input error`() {
        val sink = CapturingSink()
        ReportCommand.run(ctx(File("."), "nope.json", sink = sink)) shouldBe Exit.BAD_INPUT
        sink.err.toString() shouldContain "no such report file"
    }

    @Test
    fun `report on malformed JSON is a bad-input error`() {
        val root = createTempDirectory("report").toFile()
        try {
            File(root, "broken.json").writeText("{ not json")
            val sink = CapturingSink()
            ReportCommand.run(ctx(root, "broken.json", sink = sink)) shouldBe Exit.BAD_INPUT
            sink.err.toString() shouldContain "could not parse"
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `report with an unknown reporter is a usage error`() {
        val root = createTempDirectory("report").toFile()
        try {
            File(root, "report.json").writeText(sampleReportJson())
            val sink = CapturingSink()
            ReportCommand.run(ctx(root, "report.json", "--reporter", "xml", sink = sink)) shouldBe Exit.USAGE
            sink.err.toString() shouldContain "unknown reporter"
        } finally {
            root.deleteRecursively()
        }
    }

    // --- the default Cli wiring ---

    @Test
    fun `the default cli wires every shipped command and rejects unknown ones`() {
        val cli = Cli.default()
        listOf("analyze", "report", "rules", "explain", "inspect", "manual", "ai-loop", "doctor", "regression", "unused")
            .forEach { cli.supports(it) shouldBe true }
        cli.supports("frobnicate") shouldBe false

        // An unknown command is an honest usage error, never a silent no-op success.
        val sink = CapturingSink()
        cli.run("frobnicate", ctx(File("."), sink = sink)) shouldBe Exit.USAGE
        sink.err.toString() shouldContain "not available"
    }

    // --- rules / docs ---

    @Test
    fun `rules prints the metric catalogue`() {
        val sink = CapturingSink()
        RulesCommand.run(ctx(File("."), sink = sink)) shouldBe Exit.OK
        sink.out.toString() shouldContain "metric catalogue"
    }

    @Test
    fun `explain prints the full auto-explain for a metric id`() {
        val sink = CapturingSink()
        ExplainCommand.run(ctx(File("."), "cyclomatic-complexity", sink = sink)) shouldBe Exit.OK
        sink.out.toString() shouldContain "rationale"
        sink.out.toString() shouldContain "cyclomatic-complexity"
    }

    @Test
    fun `explain without a metric id is a usage error`() {
        val sink = CapturingSink()
        ExplainCommand.run(ctx(File("."), sink = sink)) shouldBe Exit.USAGE
        sink.err.toString() shouldContain "usage"
    }

    @Test
    fun `explain on an unknown metric id is a usage error pointing at rules`() {
        val sink = CapturingSink()
        ExplainCommand.run(ctx(File("."), "not-a-metric", sink = sink)) shouldBe Exit.USAGE
        sink.err.toString() shouldContain "unknown metric"
    }

    @Test
    fun `the embedded manual doc renders`() {
        val sink = CapturingSink()
        DocCommand.MANUAL.run(ctx(File("."), sink = sink)) shouldBe Exit.OK
        sink.out.toString().isNotBlank() shouldBe true
    }

    @Test
    fun `a doc command whose embedded resource is missing is an internal error`() {
        val sink = CapturingSink()
        DocCommand("/no-such-embedded-doc.md", "ghost").run(ctx(File("."), sink = sink)) shouldBe Exit.INTERNAL
        sink.err.toString() shouldContain "missing from this build"
    }
}
