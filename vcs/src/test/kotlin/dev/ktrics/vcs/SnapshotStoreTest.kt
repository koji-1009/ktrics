package dev.ktrics.vcs

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Span
import dev.ktrics.metric.MetricResult
import dev.ktrics.metric.ScopeKind
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Snapshot persistence: save/load round-trip and path resolution. */
class SnapshotStoreTest {
    private fun result(
        metric: String,
        scope: String,
        value: Double,
    ): MetricResult =
        MetricResult(metric, "Foo.kt", scope, scope, ScopeKind.FUNCTION, Lang.KOTLIN, value, null, Span("Foo.kt", 1, 1, 1, 1, 0, 0))

    @Test
    fun `measurements survive a save then load round-trip`() {
        val tmp = createTempDirectory("snap").toFile()
        try {
            val store = SnapshotStore(tmp)
            val file = store.resolve("baseline")
            val measurements = listOf(result("cyclomatic-complexity", "a", 10.0), result("source-lines-of-code", "a", 42.0))
            store.save(file, version = "1.0.0", measurements = measurements)

            val loaded = store.load(file)!!
            loaded.version shouldBe "1.0.0"
            loaded.measurements shouldBe measurements
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `baseline mode resolves under the project dot-ktrics dir`() {
        val tmp = createTempDirectory("snap").toFile()
        try {
            val resolved = SnapshotStore(tmp).resolve("baseline")
            resolved shouldBe File(tmp, ".ktrics/snapshot.json")
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a non-keyword mode is treated as an explicit path`() {
        SnapshotStore(File(".")).resolve("/tmp/custom.json") shouldBe File("/tmp/custom.json")
    }

    @Test
    fun `cache mode resolves to the same path as baseline`() {
        val tmp = createTempDirectory("snap").toFile()
        try {
            val store = SnapshotStore(tmp)
            // `cache` and `baseline` share one file; a regression splitting them would silently break reuse.
            store.resolve("cache") shouldBe store.resolve("baseline")
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `saving twice overwrites rather than appends`() {
        val tmp = createTempDirectory("snap").toFile()
        try {
            val store = SnapshotStore(tmp)
            val file = store.resolve("baseline")
            store.save(file, "1", listOf(result("cyclomatic-complexity", "a", 10.0)))
            store.save(file, "2", listOf(result("cyclomatic-complexity", "b", 5.0)))
            val loaded = store.load(file)!!
            loaded.version shouldBe "2"
            loaded.measurements.map { it.scope } shouldBe listOf("b") // only the second save's data remains
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a version-incompatible baseline is rejected, a same-major one is accepted`() {
        val tmp = createTempDirectory("snap").toFile()
        try {
            val store = SnapshotStore(tmp)
            val file = store.resolve("baseline")
            store.save(file, version = "1.4.0", measurements = listOf(result("cyclomatic-complexity", "a", 10.0)))
            // A different major may carry a different MetricResult shape → reject rather than silently diff.
            store.load(file, expectedVersion = "2.0.0").shouldBeNull()
            // Same major (fields stable through 0.x / within a major) → usable.
            store.load(file, expectedVersion = "1.9.9")!!.version shouldBe "1.4.0"
            // No expected version → unchecked (back-compat).
            store.load(file)!!.version shouldBe "1.4.0"
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `loading a missing file yields null`() {
        SnapshotStore(File(".")).load(File("nope-does-not-exist.json")).shouldBeNull()
    }

    @Test
    fun `loading a corrupt snapshot yields null rather than throwing`() {
        val tmp = createTempDirectory("snap").toFile()
        try {
            val file = File(tmp, "broken.json").apply { writeText("{ not valid json") }
            SnapshotStore(tmp).load(file).shouldBeNull()
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `save creates the parent directory if absent`() {
        val tmp = createTempDirectory("snap").toFile()
        try {
            val store = SnapshotStore(tmp)
            val file = store.resolve("baseline") // .ktrics/ does not exist yet
            store.save(file, "1.0.0", listOf(result("cyclomatic-complexity", "a", 3.0)))
            file.isFile shouldBe true
        } finally {
            tmp.deleteRecursively()
        }
    }
}
