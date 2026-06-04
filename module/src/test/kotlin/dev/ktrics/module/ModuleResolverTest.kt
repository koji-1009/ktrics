package dev.ktrics.module

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** Path→module ownership, graph-defined not heuristic. */
class ModuleResolverTest {
    private val projectRoot = File("/proj")
    private val graph =
        ModuleGraph(
            listOf(
                ModuleNode("app", srcRoots = listOf("app/src/main/kotlin"), dependsOn = listOf("core")),
                ModuleNode("core", srcRoots = listOf("core/src/main/kotlin")),
            ),
        )
    private val resolver = ModuleResolver(graph, projectRoot)

    @Test
    fun `moduleOf maps a file under a source root to its module`() {
        resolver.moduleOf(File("/proj/app/src/main/kotlin/pkg/Foo.kt"))?.name shouldBe "app"
        resolver.moduleOf(File("/proj/core/src/main/kotlin/pkg/Bar.kt"))?.name shouldBe "core"
    }

    @Test
    fun `a file under no declared root has no owning module`() {
        resolver.moduleOf(File("/proj/scripts/gen.kt")).shouldBeNull()
    }

    @Test
    fun `the string overload resolves relative to the project root`() {
        resolver.moduleOf("app/src/main/kotlin/Foo.kt")?.name shouldBe "app"
    }

    @Test
    fun `a nested source root wins over a shorter overlapping one (longest-first)`() {
        val nested =
            ModuleResolver(
                ModuleGraph(
                    listOf(
                        ModuleNode("outer", srcRoots = listOf("app/src")),
                        ModuleNode("inner", srcRoots = listOf("app/src/main/kotlin")),
                    ),
                ),
                projectRoot,
            )
        nested.moduleOf(File("/proj/app/src/main/kotlin/Foo.kt"))?.name shouldBe "inner"
        nested.moduleOf(File("/proj/app/src/test/Bar.kt"))?.name shouldBe "outer"
    }

    @Test
    fun `a tilde source root expands to the user home`() {
        val home = System.getProperty("user.home")
        val r = ModuleResolver(ModuleGraph(listOf(ModuleNode("ext", srcRoots = listOf("~/libsrc")))), projectRoot)
        r.moduleOf(File("$home/libsrc/pkg/X.kt"))?.name shouldBe "ext"
    }
}
