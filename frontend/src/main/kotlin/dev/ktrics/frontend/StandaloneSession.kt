package dev.ktrics.frontend

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import dev.ktrics.module.ModuleGraph
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/**
 * The single embedded K2/JetBrains analysis platform host: one Analysis API Standalone
 * session built as a [KaModule] graph (NOT a flat source root), giving Kotlin PSI + resolution and
 * the platform's Java PSI — Java and Kotlin in one shared symbol space, cross-language resolution
 * both directions.
 *
 * PINNED to the Analysis API version and upgrade-gated by the CI resolution corpus.
 * The import paths below track the pinned Kotlin Analysis API Standalone distribution; a Kotlin bump
 * may move them and MUST pass the corpus before landing — do not float the version.
 *
 * The make-or-break check: the unproven part is Java PSI bodies + Java symbols in
 * a *standalone* session. [Phase0Spike] asserts (b) Java PSI bodies materialize, (c) Kotlin↔Java
 * resolves both directions, and (d) a cross-module app→core reference resolves. If those fail the
 * single-host premise fails — surface it immediately, do not work around it silently.
 */
class KtricsSession internal constructor(
    private val api: StandaloneAnalysisAPISession,
    val moduleByName: Map<String, KaModule>,
) : AutoCloseable {
    val project: Project get() = api.project

    /** Every Kotlin source file the session loaded, across all modules. */
    val ktFiles: List<KtFile> by lazy { api.modulesWithFiles.values.flatten().filterIsInstance<KtFile>() }

    /**
     * Every Java source file the session loaded, with bodies materialized. This is the make-or-break
     * surface: body-level metrics for Java come from this PSI, never from FIR.
     */
    val javaFiles: List<PsiJavaFile> by lazy {
        api.modulesWithFiles.values.flatten().filterIsInstance<PsiJavaFile>()
    }

    override fun close() {
        // Standalone sessions hold the IntelliJ application disposable; the warm cache disposes it on
        // daemon shutdown. Best-effort: the pinned Analysis API version owns the precise disposal call.
        runCatching { (api.application as? com.intellij.openapi.Disposable)?.let { com.intellij.openapi.util.Disposer.dispose(it) } }
    }
}

/** Builds a [KtricsSession] from a declared [ModuleGraph]. */
object StandaloneSessionFactory {
    /**
     * Constructs the session as a KaModule graph: each [dev.ktrics.module.ModuleNode] becomes a
     * `KaSourceModule` (its Kotlin + Java source roots) plus `KaLibraryModule` entries (its JAR
     * classpath), with project edges to the modules it depends on. This is what makes module-crossing
     * resolution, correct Martin package boundaries, and cross-module unused-detection work.
     */
    fun build(
        graph: ModuleGraph,
        projectRoot: File,
    ): KtricsSession {
        val paths = SessionPaths(graph, projectRoot)
        val resolved = paths.resolveAll().associateBy { it.node.name }
        val kaModules = HashMap<String, KaModule>()
        val jdkHome = File(System.getProperty("java.home"))

        val stdlibJar = kotlinStdlibJar()

        val api =
            buildStandaloneAnalysisAPISession {
                buildKtModuleProvider {
                    platform = JvmPlatforms.defaultJvmPlatform

                    // The JDK as an SDK module — REQUIRED for Kotlin to resolve ANY Java: the FIR Java
                    // symbol provider is rooted on the JDK, so without it Kotlin→Java resolves NOTHING
                    // (not the JDK, not Java source, not even a compiled JAR). This is what makes
                    // cross-language Kotlin↔Java resolution real (make-or-break).
                    val jdkModule =
                        buildKtSdkModule {
                            this.platform = JvmPlatforms.defaultJvmPlatform
                            addBinaryRootsFromJdkHome(jdkHome.toPath(), isJre = false)
                            libraryName = "jdk"
                        }.also { addModule(it) }

                    // The Kotlin standard library is needed to resolve ANY Kotlin project: without it the
                    // pervasive stdlib calls (`run`/`let`/`map`/`also`/…) don't resolve and degrade to
                    // name-based edges, which then over-link by simple name in the call graph. It is
                    // universally required (unlike a project's own dependencies), so we always supply it
                    // from the daemon's own runtime rather than requiring it in every module's classpath.
                    val stdlibModule = stdlibModuleOrNull(stdlibJar)

                    // Two passes so dependency edges can reference already-built modules. The graph is a
                    // DAG (validated in ModuleGraph), so a topological order exists.
                    val order = topoOrder(graph)
                    for (name in order) {
                        val rm = resolved.getValue(name)
                        val libModules =
                            rm.classpath.map { jar ->
                                buildKtLibraryModule {
                                    this.platform = JvmPlatforms.defaultJvmPlatform
                                    addBinaryRoot(jar.toPath())
                                    libraryName = "$name:${jar.name}"
                                }
                            }
                        val source =
                            buildKtSourceModule {
                                this.platform = JvmPlatforms.defaultJvmPlatform
                                moduleName = name
                                // Kotlin AND Java source ROOTS (directories) in one module — the cross-language
                                // symbol space. Roots (not enumerated files) let the FIR Java symbol provider
                                // index Java source so Kotlin→Java source resolution materializes.
                                rm.node.srcRoots
                                    .map { absoluteRoot(projectRoot, it) }
                                    .filter { it.exists() }
                                    .forEach { addSourceRoot(it.toPath()) }
                                addRegularDependency(jdkModule)
                                stdlibModule?.let { addRegularDependency(it) }
                                libModules.forEach { addRegularDependency(it) }
                                // Project edges: app -> core enables both-direction cross-module resolution.
                                rm.node.dependsOn.forEach { dep ->
                                    kaModules[dep]?.let { addRegularDependency(it) }
                                }
                            }
                        libModules.forEach { addModule(it) }
                        addModule(source)
                        kaModules[name] = source
                    }
                }
            }
        return KtricsSession(api, kaModules)
    }

    /** Builds + registers the kotlin-stdlib library module; null when the daemon's stdlib jar wasn't located. */
    private fun KtModuleProviderBuilder.stdlibModuleOrNull(stdlibJar: File?): KaLibraryModule? {
        if (stdlibJar == null) return null
        return buildKtLibraryModule {
            this.platform = JvmPlatforms.defaultJvmPlatform
            addBinaryRoot(stdlibJar.toPath())
            libraryName = "kotlin-stdlib"
        }.also { addModule(it) }
    }

    /** The Kotlin stdlib jar, located from the daemon's own runtime (kotlin.Unit lives in it). */
    private fun kotlinStdlibJar(): File? =
        runCatching {
            val location = Unit::class.java.protectionDomain?.codeSource?.location ?: return null
            File(location.toURI()).takeIf { it.isFile && it.extension == "jar" }
        }.getOrNull()

    private fun absoluteRoot(
        projectRoot: File,
        path: String,
    ): File {
        val expanded = if (path.startsWith("~")) System.getProperty("user.home") + path.removePrefix("~") else path
        val f = File(expanded)
        return (if (f.isAbsolute) f else File(projectRoot, expanded)).absoluteFile.normalize()
    }

    /** Kahn topological order over the validated DAG so dependencies build before dependents. */
    private fun topoOrder(graph: ModuleGraph): List<String> {
        val result = ArrayList<String>()
        val visited = HashSet<String>()

        fun visit(name: String) {
            if (!visited.add(name)) return
            graph.module(name)?.dependsOn?.forEach(::visit)
            result.add(name)
        }
        graph.modules.forEach { visit(it.name) }
        return result
    }
}
