package dev.ktrics.ir

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `Resolution.weakest` is the `--apply` gate's basis: any name-based edge demotes the scope. */
class ResolutionTest {
    @Test
    fun `weakest is RESOLVED only when every edge resolved`() {
        Resolution.weakest(listOf(Resolution.RESOLVED, Resolution.RESOLVED)) shouldBe Resolution.RESOLVED
    }

    @Test
    fun `a single name-based edge demotes the whole scope`() {
        Resolution.weakest(listOf(Resolution.RESOLVED, Resolution.NAME_BASED)) shouldBe Resolution.NAME_BASED
    }

    @Test
    fun `an empty edge set is vacuously resolved`() {
        // No name-based edge present, so the worst-of fold yields RESOLVED.
        Resolution.weakest(emptyList()) shouldBe Resolution.RESOLVED
    }

    @Test
    fun `wireName renders the agent-facing confidence label`() {
        Resolution.RESOLVED.wireName shouldBe "resolved"
        Resolution.NAME_BASED.wireName shouldBe "name-based"
    }
}
