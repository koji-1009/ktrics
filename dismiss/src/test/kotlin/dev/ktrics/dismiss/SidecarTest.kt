package dev.ktrics.dismiss

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Span
import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.metric.DismissalState
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Severity
import dev.ktrics.metric.Violation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Loading the YAML dismissal sidecar and matching it by id / metric+file. */
class SidecarTest {
    private val cyclomatic = BuiltinMetrics.def("cyclomatic-complexity")!!

    private fun violation(
        id: String = "abc",
        file: String = "src/Foo.kt",
        scope: String = "com.x.f",
    ): Violation {
        val span = Span(file, 5, 1, 5, 1, 0, 0)
        return Violation(
            id = id, metricId = cyclomatic.id, severity = Severity.ERROR, polarity = cyclomatic.polarity,
            appliesTo = cyclomatic.appliesTo, file = file, scope = scope, scopeName = "f()",
            scopeKind = ScopeKind.FUNCTION, lang = Lang.KOTLIN, value = 30.0, threshold = 20.0, span = span,
            rationale = "r", refactorHints = emptyList(), references = emptyList(),
        )
    }

    @Test
    fun `load reads ktrics-dismissals-yaml from the project root`() {
        val dir = createTempDirectory("sidecar").toFile()
        try {
            File(dir, "ktrics-dismissals.yaml").writeText(
                "minReasonLength: 8\ndismissals:\n  - metric: cyclomatic-complexity\n    file: src/Foo.kt\n    reason: reviewed deeply\n",
            )
            val sidecar = Sidecar.load(dir)
            sidecar.minReasonLength shouldBe 8
            sidecar.dismissals.single().file shouldBe "src/Foo.kt"
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `load falls back to the yml extension and defaults to EMPTY when absent`() {
        val dir = createTempDirectory("sidecar").toFile()
        try {
            File(dir, "ktrics-dismissals.yml").writeText("dismissals:\n  - id: deadbeef\n    reason: a good long reason\n")
            Sidecar.load(dir).dismissals.single().id shouldBe "deadbeef"

            val empty = createTempDirectory("sidecar-empty").toFile()
            try {
                Sidecar.load(empty) shouldBe Sidecar.EMPTY
            } finally {
                empty.deleteRecursively()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a sidecar dismissal matches a violation by stable id`() {
        val applier =
            DismissalApplier(
                File("."),
                Sidecar(listOf(Dismissal(reason = "accepted by id, reviewed", source = "sidecar", id = "abc")), DEFAULT_MIN_REASON_LENGTH),
                strict = false,
            )
        applier.apply(listOf(violation(id = "abc"))).single().dismissal.shouldBeInstanceOf<DismissalState.Dismissed>()
    }

    @Test
    fun `a sidecar dismissal matches a violation by metric and file`() {
        val applier =
            DismissalApplier(
                File("."),
                Sidecar(
                    listOf(Dismissal(reason = "whole file reviewed here", source = "sidecar", metric = cyclomatic.id, file = "src/Foo.kt")),
                    DEFAULT_MIN_REASON_LENGTH,
                ),
                strict = false,
            )
        applier.apply(listOf(violation(file = "src/Foo.kt"))).single().dismissal.shouldBeInstanceOf<DismissalState.Dismissed>()
    }

    @Test
    fun `a minReasonLength that is present but invalid is coerced to the default and warned`() {
        // A non-integer and a negative both miss the (non-negative integer) contract: each falls back to the
        // default AND records a warning, rather than silently weakening dismissal gating.
        val notAnInt = Sidecar.parse("minReasonLength: oops\n")
        notAnInt.minReasonLength shouldBe DEFAULT_MIN_REASON_LENGTH
        notAnInt.warnings.single() shouldContain "invalid minReasonLength 'oops'"

        val negative = Sidecar.parse("minReasonLength: -3\n")
        negative.minReasonLength shouldBe DEFAULT_MIN_REASON_LENGTH
        negative.warnings.single() shouldContain "invalid minReasonLength '-3'"
    }

    @Test
    fun `a sidecar entry that targets nothing leaves the violation undismissed`() {
        val applier =
            DismissalApplier(
                File("."),
                Sidecar(listOf(Dismissal(reason = "reason but no selector at all", source = "sidecar")), DEFAULT_MIN_REASON_LENGTH),
                strict = false,
            )
        applier.apply(listOf(violation())).single().dismissal shouldBe DismissalState.None
    }
}
