package dev.ktrics.metric

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class StableIdTest {
    @Test
    fun `id is 16 lowercase hex chars`() {
        val id = StableId.of("src/Foo.kt", "com.x.Foo.bar", "cyclomatic-complexity")
        id shouldHaveLength 16
        id shouldMatch Regex("[0-9a-f]{16}")
    }

    @Test
    fun `id is stable across runs for the same inputs`() {
        val a = StableId.of("src/Foo.kt", "com.x.Foo.bar", "cyclomatic-complexity")
        val b = StableId.of("src/Foo.kt", "com.x.Foo.bar", "cyclomatic-complexity")
        a shouldBe b
    }

    @Test
    fun `id does not depend on value or line, so it survives edits`() {
        // The id is sha256(file|scope|metric) — no value, no line. Different metric → different id.
        val cc = StableId.of("src/Foo.kt", "com.x.Foo.bar", "cyclomatic-complexity")
        val cognitive = StableId.of("src/Foo.kt", "com.x.Foo.bar", "cognitive-complexity")
        (cc == cognitive) shouldBe false
    }

    @Test
    fun `sarif fingerprint key is the locked constant`() {
        StableId.SARIF_FINGERPRINT_KEY shouldBe "ktrics/v1"
    }
}
