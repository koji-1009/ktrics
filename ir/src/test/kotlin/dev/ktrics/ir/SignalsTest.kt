package dev.ktrics.ir

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Call-graph signals are reference info, not violations. They cross the wire to the
 * `ai`/`json` reporters, so the actual contract is the @Serializable shape: a round-trip must preserve
 * every field AND the wire key names must be stable — that, not constructor field assignment
 * (which the compiler already guarantees), is what these tests pin.
 */
class SignalsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `CallGraphSignal round-trips and keeps fan-in and fan-out as distinct wire fields`() {
        val s =
            CallGraphSignal(
                file = "pkg/Service.kt",
                scopeName = "pkg.Service.handle",
                kind = "method",
                line = 42,
                fanInCallers = 3,
                fanInCalls = 7,
                fanOutCallees = 5,
                fanOutCalls = 9,
            )
        val wire = json.encodeToString(s)
        json.decodeFromString<CallGraphSignal>(wire) shouldBe s
        // Distinct callers (blast radius) and total edges (hot path) are separate, stably-named fields.
        listOf("fanInCallers", "fanInCalls", "fanOutCallees", "fanOutCalls", "scopeName").forEach {
            wire shouldContain "\"$it\""
        }
    }

    @Test
    fun `UnusedEntry round-trips and keeps its declaration coordinates as stable wire fields`() {
        val e = UnusedEntry(file = "pkg/Api.kt", line = 10, kind = "function", name = "deadCode")
        val wire = json.encodeToString(e)
        json.decodeFromString<UnusedEntry>(wire) shouldBe e
        listOf("file", "line", "kind", "name").forEach { wire shouldContain "\"$it\"" }
    }

    @Test
    fun `StaleDismissal round-trips with its selector fields as stable wire fields`() {
        val s =
            StaleDismissal(
                source = "comment",
                file = "pkg/Api.kt",
                line = 3,
                metric = "cyclomatic-complexity",
                scope = null,
                id = null,
                reason = "was branchy before the refactor",
            )
        val wire = json.encodeToString(s)
        json.decodeFromString<StaleDismissal>(wire) shouldBe s
        listOf("source", "file", "line", "metric", "reason").forEach { wire shouldContain "\"$it\"" }
        // `where()` renders the location every reporter shares; a location-less sidecar entry
        // falls back to naming the channel.
        s.where() shouldBe "pkg/Api.kt:3"
        StaleDismissal(source = "sidecar", reason = "the violation this suppressed is gone").where() shouldBe "sidecar"
    }
}
