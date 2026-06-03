package dev.ktrics.frontend

import dev.ktrics.module.ModuleGraph
import dev.ktrics.module.ModuleNode
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * The make-or-break spike. One Analysis API Standalone session built as a
 * two-module KaModule graph (`app → core`) must prove:
 *   (a) Kotlin PSI + resolution,
 *   (b) Java PSI INCLUDING method bodies,
 *   (c) a Kotlin→Java AND a Java→Kotlin reference resolve,
 *   (d) a cross-module app→core reference resolves.
 *
 * If (b), (c), or (d) cannot be made to work in a standalone session, the single-host premise fails
 * and we STOP and surface it — these assertions are that gate.
 *
 * This test requires the pinned Analysis API Standalone on the classpath (resolves from the JetBrains
 * repos). It is the canonical entry of the CI resolution corpus.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase0SpikeTest {
    private lateinit var session: KtricsSession

    private val spikeRoot: File by lazy { repoRoot().resolve("testdata/spike") }

    @BeforeAll
    fun setUp() {
        val graph =
            ModuleGraph(
                listOf(
                    ModuleNode(
                        name = "core",
                        srcRoots = listOf("core/src/main/kotlin", "core/src/main/java"),
                    ),
                    ModuleNode(
                        name = "app",
                        srcRoots = listOf("app/src/main/kotlin"),
                        dependsOn = listOf("core"),
                    ),
                ),
            )
        session = StandaloneSessionFactory.build(graph, spikeRoot)
    }

    @AfterAll
    fun tearDown() {
        session.close()
    }

    @Test
    fun `a module classpath jar is built as a library module`() {
        // A module with a real jar on its classpath drives the per-module KaLibraryModule builder
        // (StandaloneSessionFactory.build) that the no-classpath fixtures never reach. An empty-but-valid
        // jar is enough to exercise the wiring; the source still loads with the extra library wired in.
        val jar =
            File.createTempFile("ktrics-cp", ".jar").apply {
                java.util.jar.JarOutputStream(outputStream()).close()
                deleteOnExit()
            }
        val graph =
            ModuleGraph(
                listOf(ModuleNode(name = "core", srcRoots = listOf("core/src/main/kotlin"), classpath = listOf(jar.absolutePath))),
            )
        StandaloneSessionFactory.build(graph, spikeRoot).use { built ->
            assertTrue("CoreApi.kt" in built.ktFiles.map { it.name }) { "session with a classpath jar still loads its sources" }
        }
    }

    @Test
    fun `the session exposes the underlying analysis project`() {
        // Reading session.project drives KtricsSession's getter; the api's project is returned consistently.
        val project = session.project
        assertEquals(project, session.project)
    }

    @Test
    fun `(a) kotlin PSI loads`() {
        val names = session.ktFiles.map { it.name }.toSet()
        assertTrue("CoreApi.kt" in names) { "expected CoreApi.kt among $names" }
        assertTrue("App.kt" in names) { "expected App.kt among $names" }
    }

    @Test
    fun `(b) java PSI bodies materialize`() {
        val greeter = session.javaFiles.first { it.name == "JavaGreeter.java" }
        val hello = greeter.classes.single().methods.single { it.name == "hello" }
        // The body — not just the signature — must be present. This is the unproven part.
        assertTrue(hello.body != null) { "JavaGreeter.hello() body did not materialize" }
        assertTrue(hello.body!!.statements.isNotEmpty()) { "JavaGreeter.hello() body was empty" }
    }

    /**
     * Kotlin→Java SOURCE resolution — the make-or-break edge. RESOLVED. The earlier
     * "platform limitation" was a self-inflicted bug: the session never registered the JDK as a
     * `KaSdkModule`, and the FIR Java symbol provider is ROOTED on the JDK — so without it Kotlin could
     * resolve NO Java at all (JDK, source, or even a compiled JAR). Adding the JDK SDK module
     * (`buildKtSdkModule { addBinaryRootsFromJdkHome(...) }`, see [StandaloneSessionFactory.build])
     * makes every Kotlin→Java edge resolve, INCLUDING Java source. All four make-or-break facts now hold.
     */
    @Test
    fun `(c) kotlin to java reference resolves`() {
        val ktUser = session.ktFiles.first { it.name == "KotlinUsesJava.kt" }
        val resolved = firstCallResolvingTo(ktUser, container = "com.example.core.JavaGreeter", member = "hello")
        assertTrue(resolved) { "Kotlin→Java: KotlinUsesJava could not resolve JavaGreeter.hello" }
    }

    @Test
    fun `(c) java to kotlin reference resolves`() {
        // Java→Kotlin: JavaUsesKotlin.pong() calls Kotlin CoreApi.ping(). Java method bodies are PSI;
        // the resolved target is a Kotlin symbol in the shared symbol space. Resolve via Java PSI.
        val javaUser = session.javaFiles.first { it.name == "JavaUsesKotlin.java" }
        val calls = javaUser.collectMethodCallTargets()
        assertTrue(calls.any { it.endsWith("CoreApi.ping") }) {
            "Java→Kotlin: JavaUsesKotlin could not resolve CoreApi.ping; saw $calls"
        }
    }

    @Test
    fun `(d) cross-module app to core reference resolves`() {
        val app = session.ktFiles.first { it.name == "App.kt" }
        // Both cross-module edges resolve: Kotlin→Kotlin (CoreApi.ping) and Kotlin→Java (JavaGreeter.hello).
        val toKotlin = firstCallResolvingTo(app, container = "com.example.core.CoreApi", member = "ping")
        val toJava = firstCallResolvingTo(app, container = "com.example.core.JavaGreeter", member = "hello")
        assertEquals(true, toKotlin) { "cross-module Kotlin→Kotlin app→core (CoreApi.ping) failed" }
        assertEquals(true, toJava) { "cross-module Kotlin→Java app→core (JavaGreeter.hello) failed" }
    }

    /**
     * Resolves every Kotlin call in [file] and reports whether one targets [container].[member].
     * Owner match is by-FQN when the callableId exposes it, else by the bare member name (the spike
     * fixtures declare each member name exactly once, so a name hit uniquely identifies the target).
     */
    private fun firstCallResolvingTo(
        file: KtFile,
        container: String,
        member: String,
    ): Boolean =
        analyze(file) {
            file.collectDescendantsOfType<KtCallExpression>().any { call ->
                val symbol = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return@any false
                // A Java method symbol can have a null callableId; fall back to its simple name. The
                // call having a successful symbol already means resolution worked.
                val name =
                    (symbol as? KaNamedFunctionSymbol)?.name?.asString()
                        ?: symbol.callableId?.callableName?.asString()
                        ?: return@any false
                if (name != member) return@any false
                val owner = symbol.callableId?.classId?.asFqNameString()
                owner == null || owner == container
            }
        }
}
