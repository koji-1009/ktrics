package dev.ktrics.engine

import dev.ktrics.module.ModuleGraph
import dev.ktrics.testsession.repoRoot
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** The warm-index cache: a repeated request with an unchanged key reuses the session. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarmIndexCacheTest {
    @AfterAll
    fun tearDown() = WarmIndexCache.clear()

    @Test
    fun `a second get with the same key and fingerprint returns the cached index`() {
        val root = repoRoot().resolve("testdata/analyze")
        val graph = ModuleGraph.singleModule(srcRoots = listOf("src/main/kotlin"))
        val first = WarmIndexCache.get(root, graph, configHash = "h")
        val second = WarmIndexCache.get(root, graph, configHash = "h") // unchanged key+fingerprint → cache hit
        (first === second) shouldBe true
    }

    @Test
    fun `withWarm hands the same warm index a bare get would`() {
        val root = repoRoot().resolve("testdata/analyze")
        val graph = ModuleGraph.singleModule(srcRoots = listOf("src/main/kotlin"))
        val viaGet = WarmIndexCache.get(root, graph, configHash = "h")
        val viaWith = WarmIndexCache.withWarm(root, graph, configHash = "h") { it }
        (viaGet === viaWith) shouldBe true
    }

    @Test
    fun `evict drops the entry so the next get rebuilds`() {
        // Regression's temp worktrees never repeat their keys — without evict every run would leak a
        // live session (IntelliJ application + PSI) rooted at a deleted directory, forever.
        val root = repoRoot().resolve("testdata/analyze")
        val graph = ModuleGraph.singleModule(srcRoots = listOf("src/main/kotlin"))
        val first = WarmIndexCache.get(root, graph, configHash = "evict-me")
        WarmIndexCache.evict(root)
        val second = WarmIndexCache.get(root, graph, configHash = "evict-me")
        (first === second) shouldBe false
    }

    @Test
    fun `the cache is LRU-bounded - the eldest entry is closed and forgotten past the cap`() {
        // A busy daemon serving many distinct (root, config) keys must not pin every session forever.
        // The cap is lowered for the test (the eviction logic is cap-independent) so this needs three
        // sessions, not nine — nine real platform boots exhaust the test JVM.
        WarmIndexCache.clear()
        WarmIndexCache.maxEntries = 2
        try {
            val root = repoRoot().resolve("testdata/analyze")
            val graph = ModuleGraph.singleModule(srcRoots = listOf("src/main/kotlin"))
            val eldest = WarmIndexCache.get(root, graph, configHash = "lru-0")
            WarmIndexCache.get(root, graph, configHash = "lru-1")
            WarmIndexCache.get(root, graph, configHash = "lru-2") // exceeds the cap → lru-0 evicted
            val rebuilt = WarmIndexCache.get(root, graph, configHash = "lru-0")
            (eldest === rebuilt) shouldBe false
        } finally {
            WarmIndexCache.maxEntries = 8
            WarmIndexCache.clear()
        }
    }
}
