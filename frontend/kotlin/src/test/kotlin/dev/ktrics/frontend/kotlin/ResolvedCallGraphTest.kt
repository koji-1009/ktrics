package dev.ktrics.frontend.kotlin

import dev.ktrics.frontend.KtricsSession
import dev.ktrics.frontend.StandaloneSessionFactory
import dev.ktrics.module.ModuleGraph
import dev.ktrics.module.ModuleNode
import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Proves the call-graph edges are RESOLUTION-backed, not simple-name-based: in a real standalone
 * session over the `app → core` spike, [ResolvedKotlinClassifier.outgoingRefNames] returns each call's
 * fully-qualified, OWNER-disambiguated key. This is the mechanism that lets `inspect`/`signals` tell
 * `A.run` from `B.run` instead of conflating every same-named declaration — so the "name-based" reading
 * is only ever a fallback for an edge the classpath genuinely cannot resolve, never an inherent limit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResolvedCallGraphTest {
    private lateinit var session: KtricsSession

    @BeforeAll
    fun setUp() {
        val graph =
            ModuleGraph(
                listOf(
                    ModuleNode("core", srcRoots = listOf("core/src/main/kotlin", "core/src/main/java")),
                    ModuleNode("app", srcRoots = listOf("app/src/main/kotlin"), dependsOn = listOf("core")),
                ),
            )
        session = StandaloneSessionFactory.build(graph, repoRoot().resolve("testdata/spike"))
    }

    @AfterAll
    fun tearDown() = session.close()

    @Test
    fun `outgoing call edges resolve to owner-qualified keys`() {
        val app = session.ktFiles.first { it.name == "App.kt" }
        val refs = ResolvedKotlinClassifier().outgoingRefNames(app)
        // App.run() calls CoreApi().ping() — the resolved key carries the owner, so a homonym `ping`
        // on a different type could never collide with it (the simple name `ping` alone would).
        refs shouldContain "com.example.core.CoreApi.ping"
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate repo root from ${System.getProperty("user.dir")}")
    }
}
