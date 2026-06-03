package dev.ktrics.engine

import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandSink
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Module-graph source order: ktrics.yaml → --module flags → single-module fallback. */
class GraphSourceTest {
    private object NoSink : CommandSink {
        override fun out(text: String) {}

        override fun err(text: String) {}
    }

    private fun ctx(
        root: File,
        vararg args: String,
    ) = CommandContext(args.toList(), root, emptyMap(), NoSink, cwd = root)

    @Test
    fun `with no config and no flags it falls back to a single module over the target`() {
        val root = createTempDirectory("gs").toFile()
        try {
            val target = root
            val resolved = GraphSource.resolve(ctx(root), target)
            resolved.graph.modules.size shouldBe 1
            resolved.graph.modules.single().srcRoots shouldBe listOf(target.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `repeated --module flags build a multi-module graph with dependencies`() {
        val root = createTempDirectory("gs").toFile()
        try {
            val resolved =
                GraphSource.resolve(
                    ctx(root, "--module", "app=app/src/main,app/src/test:core", "--module", "core=core/src/main"),
                    root,
                )
            resolved.graph.modules.map { it.name } shouldContainExactlyInAnyOrder listOf("app", "core")
            val app = resolved.graph.module("app")!!
            app.srcRoots shouldBe listOf("app/src/main", "app/src/test")
            app.dependsOn shouldBe listOf("core")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a malformed --module spec without an equals sign is skipped`() {
        val root = createTempDirectory("gs").toFile()
        try {
            // "garbage" has no `=` → dropped by the mapNotNull; the valid spec still builds its module.
            val resolved = GraphSource.resolve(ctx(root, "--module", "garbage", "--module", "app=app/src"), root)
            resolved.graph.modules.map { it.name } shouldContainExactlyInAnyOrder listOf("app")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `when every --module spec is malformed it falls back to a single module`() {
        val root = createTempDirectory("gs").toFile()
        try {
            // all specs malformed → cliModules yields no nodes → single-module fallback over the target.
            GraphSource.resolve(ctx(root, "--module", "no-equals-here"), root).graph.modules.size shouldBe 1
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a declared graph in ktrics-yaml takes precedence over the fallback`() {
        val root = createTempDirectory("gs").toFile()
        try {
            File(root, "ktrics.yaml").writeText(
                """
                ktrics:
                  modules:
                    declared:
                      - name: core
                        srcRoots: [core/src/main/kotlin]
                      - name: app
                        srcRoots: [app/src/main/kotlin]
                        dependsOn: [core]
                """.trimIndent(),
            )
            val resolved = GraphSource.resolve(ctx(root), root)
            resolved.graph.modules.map { it.name } shouldContainExactlyInAnyOrder listOf("core", "app")
            resolved.graph.module("app")!!.dependsOn shouldBe listOf("core")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `explicit name-based resolution mode disables resolution`() {
        val root = createTempDirectory("gs").toFile()
        try {
            File(root, "ktrics.yaml").writeText("ktrics:\n  resolution: name-based\n")
            GraphSource.resolve(ctx(root), root).resolved shouldBe false
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `auto resolution mode keeps resolution on`() {
        val root = createTempDirectory("gs").toFile()
        try {
            GraphSource.resolve(ctx(root), root).resolved shouldBe true
        } finally {
            root.deleteRecursively()
        }
    }
}
