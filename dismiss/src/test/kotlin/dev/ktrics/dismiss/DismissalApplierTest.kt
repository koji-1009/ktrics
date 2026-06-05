package dev.ktrics.dismiss

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Span
import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.metric.DismissalState
import dev.ktrics.metric.MetricResult
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Severity
import dev.ktrics.metric.Violation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Two dismissal channels: sidecar > comment, short-reason rejection, --strict. */
class DismissalApplierTest {
    private val cyclomatic = BuiltinMetrics.def("cyclomatic-complexity")!!

    private fun violation(
        file: String = "src/Foo.kt",
        scope: String = "com.x.Foo.bar",
        line: Int = 5,
    ): Violation {
        val span = Span(file, line, 1, line, 1, 0, 0)
        val result =
            MetricResult(
                metricId = cyclomatic.id,
                file = file,
                scope = scope,
                scopeName = "Foo.bar()",
                scopeKind = ScopeKind.FUNCTION,
                lang = Lang.KOTLIN,
                value = 30.0,
                span = span,
            )
        return Violation.from(result, cyclomatic, Severity.ERROR, threshold = 20.0)
    }

    private fun sidecar(
        vararg dismissals: Dismissal,
        minReason: Int = DEFAULT_MIN_REASON_LENGTH,
    ) = Sidecar(dismissals.toList(), minReason)

    @Test
    fun `a sidecar entry with a long-enough reason dismisses the violation`() {
        val v = violation()
        val applier =
            DismissalApplier(
                File("."),
                sidecar(
                    Dismissal(reason = "reviewed: intentional state machine", source = "sidecar", metric = cyclomatic.id, scope = v.scope),
                ),
                strict = false,
            )
        val result = applier.apply(listOf(v)).single()
        val dismissal = result.dismissal
        dismissal.shouldBeInstanceOf<DismissalState.Dismissed>()
        dismissal.source shouldBe "sidecar"
    }

    @Test
    fun `a reason shorter than minReasonLength keeps the violation live as Rejected`() {
        val v = violation()
        val applier =
            DismissalApplier(
                File("."),
                sidecar(Dismissal(reason = "short", source = "sidecar", metric = cyclomatic.id, scope = v.scope), minReason = 12),
                strict = false,
            )
        val result = applier.apply(listOf(v)).single()
        result.dismissal.shouldBeInstanceOf<DismissalState.Rejected>()
        // Rejected violations are still LIVE (not suppressed) — partition keeps them on the live side.
        val (live, dismissed) = applier.partition(listOf(v))
        live.size shouldBe 1
        dismissed.size shouldBe 0
    }

    @Test
    fun `--strict ignores all dismissals and leaves every violation live and undismissed`() {
        val v = violation()
        val applier =
            DismissalApplier(
                File("."),
                sidecar(
                    Dismissal(reason = "reviewed: intentional state machine", source = "sidecar", metric = cyclomatic.id, scope = v.scope),
                ),
                strict = true,
            )
        applier.apply(listOf(v)).single().dismissal shouldBe DismissalState.None
        applier.partition(listOf(v)).first.size shouldBe 1
    }

    @Test
    fun `the sidecar wins when both channels target the same violation`() {
        val tmp = createTempDirectory("dismiss").toFile()
        try {
            val relPath = "Foo.kt"
            // A comment dismissal sits directly above the declaration on line 2.
            File(tmp, relPath).writeText(
                """
                // ktrics:dismiss cyclomatic-complexity reason="comment channel reason"
                fun bar() {}
                """.trimIndent(),
            )
            val v = violation(file = relPath, line = 2)
            val applier =
                DismissalApplier(
                    tmp,
                    sidecar(Dismissal(reason = "sidecar channel reason here", source = "sidecar", metric = cyclomatic.id, scope = v.scope)),
                    strict = false,
                )
            val dismissal = applier.apply(listOf(v)).single().dismissal
            dismissal.shouldBeInstanceOf<DismissalState.Dismissed>()
            // Sidecar wins on collision.
            dismissal.source shouldBe "sidecar"
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a comment dismissal applies when there is no sidecar match`() {
        val tmp = createTempDirectory("dismiss").toFile()
        try {
            val relPath = "Foo.kt"
            File(tmp, relPath).writeText(
                """
                // ktrics:dismiss cyclomatic-complexity reason="reviewed in PR 42, accepted"
                fun bar() {}
                """.trimIndent(),
            )
            val v = violation(file = relPath, line = 2)
            val applier = DismissalApplier(tmp, Sidecar.EMPTY, strict = false)
            val dismissal = applier.apply(listOf(v)).single().dismissal
            dismissal.shouldBeInstanceOf<DismissalState.Dismissed>()
            dismissal.source shouldBe "comment"
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `an unmatched violation is left undismissed`() {
        val applier = DismissalApplier(File("."), Sidecar.EMPTY, strict = false)
        applier.apply(listOf(violation())).single().dismissal shouldBe DismissalState.None
    }

    @Test
    fun `the reason-length gate is boundary-exact`() {
        val v = violation()

        fun apply(
            reason: String,
            min: Int,
        ) = DismissalApplier(
            File("."),
            sidecar(Dismissal(reason = reason, source = "sidecar", metric = cyclomatic.id, scope = v.scope), minReason = min),
            strict = false,
        ).apply(listOf(v)).single().dismissal

        // The gate is `reason.length < minReasonLength`: a reason of exactly the minimum is accepted...
        apply("x".repeat(12), 12).shouldBeInstanceOf<DismissalState.Dismissed>()
        // ...and one character short is rejected (the off-by-one boundary).
        apply("x".repeat(11), 12).shouldBeInstanceOf<DismissalState.Rejected>()
    }

    @Test
    fun `a sidecar id match wins even when its scope does not match the violation`() {
        // Selector precedence: a present id short-circuits, so scope is ignored (id is the stablest key).
        val v = violation()
        val applier =
            DismissalApplier(
                File("."),
                sidecar(
                    Dismissal(reason = "matched by the stable id, not the scope", source = "sidecar", id = v.id, scope = "com.other.Wrong"),
                ),
                strict = false,
            )
        applier.apply(listOf(v)).single().dismissal.shouldBeInstanceOf<DismissalState.Dismissed>()
    }

    @Test
    fun `a sidecar entry carrying only a metric (no scope or file) matches nothing`() {
        // metric set but scope AND file null → the `else -> false` branch; a metric alone is too broad to target.
        val v = violation()
        val applier =
            DismissalApplier(
                File("."),
                sidecar(Dismissal(reason = "too broad: a metric with no target", source = "sidecar", metric = cyclomatic.id)),
                strict = false,
            )
        applier.apply(listOf(v)).single().dismissal shouldBe DismissalState.None
    }

    // --- stale detection: directives whose violation no longer fires ---

    @Test
    fun `an unmatched sidecar entry is reported stale and a consumed one is not`() {
        val v = violation()
        val consumed =
            Dismissal(reason = "reviewed: intentional state machine", source = "sidecar", metric = cyclomatic.id, scope = v.scope)
        val dead =
            Dismissal(reason = "the violation this suppressed is gone", source = "sidecar", metric = cyclomatic.id, scope = "com.x.Gone.fn")
        val applier = DismissalApplier(File("."), sidecar(consumed, dead), strict = false)
        val stale = applier.staleDismissals(listOf(v), analyzedFiles = emptyList())
        stale.single().scope shouldBe "com.x.Gone.fn"
        stale.single().source shouldBe "sidecar"
    }

    @Test
    fun `an unmatched comment directive is reported stale with its file and line`() {
        val dir = createTempDirectory("stale").toFile()
        try {
            // The directive precedes a function that no longer violates anything.
            File(dir, "src").mkdirs()
            File(dir, "src/Foo.kt").writeText(
                """
                // ktrics:dismiss cyclomatic-complexity reason="was branchy before the refactor"
                fun fine() = 1
                """.trimIndent(),
            )
            val applier = DismissalApplier(dir, Sidecar.EMPTY, strict = false)
            val stale = applier.staleDismissals(emptyList(), analyzedFiles = listOf("src/Foo.kt"))
            stale.single().source shouldBe "comment"
            stale.single().file shouldBe "src/Foo.kt"
            stale.single().line shouldBe 1
            stale.single().metric shouldBe "cyclomatic-complexity"
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a comment directive consumed by a live violation is not stale`() {
        val dir = createTempDirectory("consumed").toFile()
        try {
            File(dir, "src").mkdirs()
            File(dir, "src/Foo.kt").writeText(
                """
                // ktrics:dismiss cyclomatic-complexity reason="reviewed: intentional state machine"
                fun bar() = 1
                """.trimIndent(),
            )
            // The violation's span starts at the declaration line the directive precedes.
            val v = violation(file = "src/Foo.kt", line = 2)
            val applier = DismissalApplier(dir, Sidecar.EMPTY, strict = false)
            applier.staleDismissals(listOf(v), analyzedFiles = listOf("src/Foo.kt")) shouldBe emptyList()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `strict mode reports no stale dismissals`() {
        // Under --strict-dismiss every directive is ignored, so everything would read stale — noise.
        val dead = Dismissal(reason = "anything at all goes here now", source = "sidecar", metric = cyclomatic.id, scope = "x.Gone")
        DismissalApplier(File("."), sidecar(dead), strict = true).staleDismissals(emptyList(), emptyList()) shouldBe emptyList()
    }
}
