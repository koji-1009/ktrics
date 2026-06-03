package dev.ktrics.testsession

import com.intellij.psi.PsiElement
import dev.ktrics.frontend.KtricsSession
import dev.ktrics.frontend.StandaloneSessionFactory
import dev.ktrics.frontend.java.JavaFrontend
import dev.ktrics.frontend.kotlin.KotlinFrontend
import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl
import dev.ktrics.langapi.FrontendRegistry
import dev.ktrics.langapi.LanguageFrontend
import dev.ktrics.langapi.NodeClassifier
import dev.ktrics.module.ModuleGraph
import dev.ktrics.module.ModuleNode
import java.io.File

/**
 * A real standalone Analysis API session over a small `testdata/<name>` project, plus the IR lowering
 * and classifiers built on top of it — the generalization of `Phase0SpikeTest`/`ResolvedCallGraphTest`
 * the metric and frontend tests reuse.
 *
 * Construct once per test class (it is moderately expensive — it boots the platform). The [resolved]
 * flag picks the resolution-backed classifiers (`analyze {}` / Java `.resolve()`) over the syntactic
 * ones, so a test can assert both name-based and resolved behaviour from the same sources.
 */
class SessionFixture(
    graph: ModuleGraph,
    val projectRoot: File,
    private val resolved: Boolean = false,
) : AutoCloseable {
    val session: KtricsSession = StandaloneSessionFactory.build(graph, projectRoot)

    private val kotlinFrontend: LanguageFrontend = KotlinFrontend(projectRoot, resolved)
    private val javaFrontend: LanguageFrontend = JavaFrontend(projectRoot, resolved)
    private val registry = FrontendRegistry(listOf(kotlinFrontend, javaFrontend))

    /** The classifier for [lang] at this fixture's resolution setting. */
    fun classifier(lang: Lang): NodeClassifier = requireNotNull(registry.forLang(lang)) { "no frontend for $lang" }.classifier

    /** Lowers a single loaded file (Kotlin or Java) to IR. */
    fun lower(file: PsiElement): SourceUnit = requireNotNull(registry.forFile(file)) { "no frontend accepts $file" }.lower(file)

    /** Lowers the Kotlin file with the given simple name (e.g. `App.kt`). */
    fun kotlinUnit(fileName: String): SourceUnit = lower(session.ktFiles.first { it.name == fileName })

    /** Lowers the Java file with the given simple name (e.g. `Foo.java`). */
    fun javaUnit(fileName: String): SourceUnit = lower(session.javaFiles.first { it.name == fileName })

    /** Every Kotlin source file, lowered to IR. */
    fun kotlinUnits(): List<SourceUnit> = session.ktFiles.map { lower(it) }

    /** Every Java source file, lowered to IR. */
    fun javaUnits(): List<SourceUnit> = session.javaFiles.map { lower(it) }

    /** Every source file (Kotlin + Java), lowered to IR. */
    fun allUnits(): List<SourceUnit> = kotlinUnits() + javaUnits()

    override fun close() = session.close()

    companion object {
        /** Builds a single-module graph rooted at [srcRoots] under a testdata project. */
        fun singleModule(
            name: String = "main",
            srcRoots: List<String>,
            dependsOn: List<String> = emptyList(),
        ): ModuleGraph = ModuleGraph(listOf(ModuleNode(name, srcRoots = srcRoots, dependsOn = dependsOn)))
    }
}

/** Finds the lowered function with [name] anywhere in the unit (top-level or nested), or fails. */
fun SourceUnit.function(name: String): FunctionDecl =
    allFunctions().firstOrNull { it.name == name }
        ?: error("no function '$name' in $path; saw ${allFunctions().map { it.name }.toList()}")

/** Finds the lowered type with [name] anywhere in the unit (top-level or nested), or fails. */
fun SourceUnit.type(name: String): TypeDecl =
    allTypes().firstOrNull { it.name == name }
        ?: error("no type '$name' in $path; saw ${allTypes().map { it.name }.toList()}")

/** Walks up from the working directory to the repo root (the dir holding settings.gradle.kts). */
fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").exists()) return dir
        dir = dir.parentFile
    }
    error("Could not locate repo root from ${System.getProperty("user.dir")}")
}
