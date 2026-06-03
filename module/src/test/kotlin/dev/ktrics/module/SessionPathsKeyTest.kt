package dev.ktrics.module

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Enumerates the analyzable source files under a module graph for the warm-cache fingerprint. */
class SessionPathsKeyTest {
    @Test
    fun `it walks the source roots and keeps only analyzable files`() {
        val root = createTempDirectory("paths").toFile()
        try {
            File(root, "src/main/kotlin").mkdirs()
            File(root, "src/main/kotlin/A.kt").writeText("class A")
            File(root, "src/main/kotlin/B.java").writeText("class B {}")
            File(root, "src/main/kotlin/build.gradle.kts").writeText("// build script, excluded")
            File(root, "src/main/kotlin/notes.txt").writeText("not source")

            val graph = ModuleGraph.singleModule(srcRoots = listOf("src/main/kotlin"))
            val files = SessionPathsKey.sourceFiles(graph, root).map { it.name }
            files shouldContainExactlyInAnyOrder listOf("A.kt", "B.java")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a non-existent source root contributes nothing`() {
        val root = createTempDirectory("paths").toFile()
        try {
            val graph = ModuleGraph.singleModule(srcRoots = listOf("does/not/exist"))
            SessionPathsKey.sourceFiles(graph, root) shouldBe emptyList()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the same file under two roots is de-duplicated`() {
        val root = createTempDirectory("paths").toFile()
        try {
            File(root, "src").mkdirs()
            File(root, "src/A.kt").writeText("class A")
            // Two overlapping roots ("src" and "src/.") that both reach A.kt → it appears once.
            val graph = ModuleGraph(listOf(ModuleNode("m", srcRoots = listOf("src", "src/."))))
            SessionPathsKey.sourceFiles(graph, root).size shouldBe 1
        } finally {
            root.deleteRecursively()
        }
    }
}
