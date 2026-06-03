package dev.ktrics.frontend

import dev.ktrics.module.ModuleGraph
import dev.ktrics.module.ModuleNode
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Resolves a module graph's declared paths into concrete source files and classpath. */
class SessionPathsTest {
    @Test
    fun `resolve splits kotlin and java sources, excludes build scripts, and keeps existing classpath`() {
        val root = createTempDirectory("sessionpaths").toFile()
        try {
            File(root, "src").mkdirs()
            File(root, "src/A.kt").writeText("class A")
            File(root, "src/C.kts").writeText("val c = 1")
            File(root, "src/B.java").writeText("class B {}")
            File(root, "src/build.gradle.kts").writeText("// build script, excluded")
            File(root, "libs").mkdirs()
            val jar = File(root, "libs/present.jar").apply { writeText("jar") }

            val node = ModuleNode("m", srcRoots = listOf("src"), classpath = listOf("libs/present.jar", "libs/missing.jar"))
            val resolved = SessionPaths(ModuleGraph(listOf(node)), root).resolve(node)

            resolved.kotlinFiles.map { it.name } shouldContainExactlyInAnyOrder listOf("A.kt", "C.kts")
            resolved.javaFiles.map { it.name } shouldContainExactlyInAnyOrder listOf("B.java")
            resolved.classpath.map { it.name } shouldBe listOf("present.jar") // the missing jar is filtered out
            resolved.allSourceFiles.size shouldBe 3
            jar.exists() shouldBe true
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveAll resolves every module and a non-existent root yields no files`() {
        val root = createTempDirectory("sessionpaths").toFile()
        try {
            val graph = ModuleGraph(listOf(ModuleNode("ghost", srcRoots = listOf("nope"))))
            val all = SessionPaths(graph, root).resolveAll()
            all.single().allSourceFiles shouldBe emptyList()
        } finally {
            root.deleteRecursively()
        }
    }
}
