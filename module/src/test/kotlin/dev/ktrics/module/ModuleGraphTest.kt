package dev.ktrics.module

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ModuleGraphTest {
    @Test
    fun `a dependency cycle is rejected`() {
        shouldThrow<IllegalArgumentException> {
            ModuleGraph(
                listOf(
                    ModuleNode("a", listOf("a"), dependsOn = listOf("b")),
                    ModuleNode("b", listOf("b"), dependsOn = listOf("a")),
                ),
            )
        }
    }

    @Test
    fun `a self-dependency is rejected as a cycle`() {
        shouldThrow<IllegalArgumentException> {
            ModuleGraph(listOf(ModuleNode("a", listOf("a"), dependsOn = listOf("a"))))
        }
    }

    @Test
    fun `a three-node cycle is rejected and the message names the path`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                ModuleGraph(
                    listOf(
                        ModuleNode("a", listOf("a"), dependsOn = listOf("b")),
                        ModuleNode("b", listOf("b"), dependsOn = listOf("c")),
                        ModuleNode("c", listOf("c"), dependsOn = listOf("a")),
                    ),
                )
            }
        ex.message!! shouldContain "cycle"
        // cyclePath() reconstructs the offending chain; all three nodes appear in the diagnostic.
        listOf("a", "b", "c").forEach { ex.message!! shouldContain it }
    }

    @Test
    fun `a diamond dependency graph is acyclic`() {
        // a → {b, c}; b → d; c → d. A shared transitive dependency is NOT a cycle — the tri-colour DFS
        // must accept it (a naive "already visited" guard would wrongly reject the second path into d).
        val graph =
            ModuleGraph(
                listOf(
                    ModuleNode("a", listOf("a"), dependsOn = listOf("b", "c")),
                    ModuleNode("b", listOf("b"), dependsOn = listOf("d")),
                    ModuleNode("c", listOf("c"), dependsOn = listOf("d")),
                    ModuleNode("d", listOf("d")),
                ),
            )
        graph.cyclePath() shouldBe null
    }

    @Test
    fun `an unknown dependency is rejected`() {
        shouldThrow<IllegalArgumentException> {
            ModuleGraph(listOf(ModuleNode("a", listOf("a"), dependsOn = listOf("ghost"))))
        }
    }

    @Test
    fun `duplicate module names are rejected`() {
        shouldThrow<IllegalArgumentException> {
            ModuleGraph(listOf(ModuleNode("dup", listOf("a")), ModuleNode("dup", listOf("b"))))
        }
    }

    @Test
    fun `module looks up a node by name`() {
        val graph =
            ModuleGraph(
                listOf(
                    ModuleNode("app", listOf("app/src"), dependsOn = listOf("feature", "core")),
                    ModuleNode("feature", listOf("feature/src")),
                    ModuleNode("core", listOf("core/src")),
                ),
            )
        graph.module("feature")!!.srcRoots shouldBe listOf("feature/src")
        graph.module("ghost") shouldBe null
    }

    @Test
    fun `single-module fallback is valid`() {
        ModuleGraph.singleModule(listOf("src")).modules.single().name shouldBe "root"
    }
}
