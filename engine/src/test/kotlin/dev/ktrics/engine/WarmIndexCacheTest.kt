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
}
