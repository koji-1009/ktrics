package dev.ktrics.metric

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The builtin catalogue is the contract surface of the tool; pin its well-formedness. */
class BuiltinMetricsTest {
    @Test
    fun `every scope list contributes to the flattened catalogue`() {
        val byScope =
            BuiltinMetrics.function.size + BuiltinMetrics.type.size +
                BuiltinMetrics.file.size + BuiltinMetrics.pkg.size
        BuiltinMetrics.all.size shouldBe byScope
        (BuiltinMetrics.all.size > 20) shouldBe true
    }

    @Test
    fun `metric ids are unique`() {
        val ids = BuiltinMetrics.all.map { it.id }
        ids.toSet().size shouldBe ids.size
    }

    @Test
    fun `def resolves a known id and is null for an unknown one`() {
        BuiltinMetrics.def("cyclomatic-complexity")!!.id shouldBe "cyclomatic-complexity"
        BuiltinMetrics.def("not-a-metric").shouldBeNull()
    }

    @Test
    fun `ids exposes every catalogue id`() {
        BuiltinMetrics.ids() shouldContainAll
            listOf(
                "cyclomatic-complexity", "lcom4", "coupling-between-objects",
                "top-level-declarations-per-file", "distance-from-main-sequence",
            )
        BuiltinMetrics.ids().size shouldBe BuiltinMetrics.all.size
    }

    @Test
    fun `every metric carries auto-explain rationale and a scope-matching kind`() {
        BuiltinMetrics.function.forEach { it.def.scopeKind shouldBe ScopeKind.FUNCTION }
        BuiltinMetrics.type.forEach { it.def.scopeKind shouldBe ScopeKind.TYPE }
        BuiltinMetrics.file.forEach { it.def.scopeKind shouldBe ScopeKind.FILE }
        BuiltinMetrics.pkg.forEach { it.def.scopeKind shouldBe ScopeKind.PACKAGE }
        BuiltinMetrics.all.forEach { (it.rationale.isNotBlank()) shouldBe true }
    }
}
