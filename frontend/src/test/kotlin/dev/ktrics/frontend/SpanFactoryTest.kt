package dev.ktrics.frontend

import dev.ktrics.module.ModuleGraph
import dev.ktrics.module.ModuleNode
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * [SpanFactory] computes 1-based line/column spans from PSI offsets by binary-searching cached line
 * starts — pure arithmetic, but it needs a real [com.intellij.psi.PsiFile], so it is exercised over a
 * standalone session here rather than left to the lowering path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpanFactoryTest {
    private lateinit var session: KtricsSession
    private lateinit var ktFile: KtFile
    private lateinit var spans: SpanFactory

    @BeforeAll
    fun setUp() {
        val graph = ModuleGraph(listOf(ModuleNode("main", srcRoots = listOf("src/main/kotlin"))))
        session = StandaloneSessionFactory.build(graph, repoRoot().resolve("testdata/metrics"))
        ktFile = session.ktFiles.first { it.name == "KShapes.kt" }
        spans = SpanFactory(ktFile, "KShapes.kt")
    }

    @AfterAll
    fun tearDown() = session.close()

    @Test
    fun `wholeFile spans line 1 to the end of the file`() {
        val whole = spans.wholeFile()
        assertEquals("KShapes.kt", whole.file)
        assertEquals(1, whole.startLine)
        assertEquals(1, whole.startColumn)
        assertEquals(0, whole.startOffset)
        assertEquals(ktFile.text.length, whole.endOffset)
        assertTrue(whole.endLine > 1) { "expected a multi-line file, got endLine=${whole.endLine}" }
    }

    @Test
    fun `of maps an offset on a later line to a 1-based line and column`() {
        // The class declaration sits well below line 1; the binary search must resolve a line > 1.
        val classDecl = ktFile.declarations.first()
        val span = spans.of(classDecl)
        assertTrue(span.startLine > 1) { "class should not start on line 1, got ${span.startLine}" }
        assertTrue(span.startColumn >= 1)
        assertTrue(span.endOffset > span.startOffset)
        assertEquals(classDecl.textRange.startOffset, span.startOffset)
    }

    @Test
    fun `column is the offset within its line`() {
        // A method declared with leading indentation: its start column reflects that indent (> 1).
        val method =
            ktFile.declarations
                .filterIsInstance<org.jetbrains.kotlin.psi.KtClassOrObject>()
                .first()
                .declarations
                .first()
        val span = spans.of(method)
        assertTrue(span.startColumn > 1) { "indented member should have column > 1, got ${span.startColumn}" }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate repo root")
    }
}
