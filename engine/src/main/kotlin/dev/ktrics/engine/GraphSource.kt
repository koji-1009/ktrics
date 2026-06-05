package dev.ktrics.engine

import dev.ktrics.config.ConfigLoader
import dev.ktrics.config.ConfigMetricSettings
import dev.ktrics.config.ConfigSkipPolicy
import dev.ktrics.config.KtricsConfig
import dev.ktrics.config.ResolutionMode
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.metric.MetricSettings
import dev.ktrics.metric.SkipPolicy
import dev.ktrics.module.ModuleGraph
import dev.ktrics.module.ModuleNode
import java.io.File

/** The module graph + effective metric settings + idiom skips an analysis runs against. */
data class ResolvedProject(
    val graph: ModuleGraph,
    val settings: MetricSettings = MetricSettings.Default,
    val skips: SkipPolicy = SkipPolicy.None,
    val config: KtricsConfig = KtricsConfig.DEFAULT,
    /** Identity of the effective config, for warm-cache keying. */
    val configHash: String = "",
    /** Resolution turned on (auto/resolved → true; explicit name-based → false). */
    val resolved: Boolean = true,
    /** Config problems (parse failure, bad threshold) — commands surface them and exit 78. */
    val problems: List<String> = emptyList(),
    /** Non-fatal config advisories (unknown keys) — surfaced on stderr, never fatal. */
    val warnings: List<String> = emptyList(),
)

/**
 * Resolves the module graph + metric settings + idiom skips for a run. The module
 * graph comes from (in order): the declared graph in ktrics.yaml; repeated `--module` flags; else a
 * single module over the target path. Modeling modules is v1; auto-discovering them is v2 — never
 * conflated. The config's metric thresholds, presets and skips flow through here.
 */
object GraphSource {
    fun resolve(
        ctx: CommandContext,
        target: File,
    ): ResolvedProject {
        val load = ConfigLoader.load(ctx.projectRoot, ctx.option("--config")?.let { ctx.resolvePath(it) })
        val config = load.config
        val graph =
            config.modules.toGraph()
                ?: cliModules(ctx)
                ?: AnalysisEngine.singleModuleGraph(target)
        return ResolvedProject(
            graph = graph,
            settings = ConfigMetricSettings(config),
            skips = ConfigSkipPolicy(config),
            config = config,
            configHash = load.hash,
            // Resolution default is `auto`: resolve when possible, degrade per-edge.
            resolved = config.resolution != ResolutionMode.NAME_BASED,
            problems = load.problems,
            warnings = load.warnings,
        )
    }

    /**
     * Surfaces config diagnostics on stderr; returns false when problems make the config unusable —
     * the command must exit 78 (BAD_CONFIG). Previously a malformed `ktrics.yaml` silently ran with
     * built-in defaults and exited 0, so an agent believed its configured gates passed when they
     * were never applied.
     */
    fun reportConfig(
        ctx: CommandContext,
        resolved: ResolvedProject,
    ): Boolean {
        resolved.warnings.forEach { ctx.sink.errLine("ktrics: config warning: $it") }
        resolved.problems.forEach { ctx.sink.errLine("ktrics: config error: $it") }
        return resolved.problems.isEmpty()
    }

    /** Parses repeated `--module name=src1,src2[:dep1,dep2]` flags into a graph. */
    private fun cliModules(ctx: CommandContext): ModuleGraph? {
        val specs = ctx.args.zipWithNext().filter { it.first == "--module" }.map { it.second }
        if (specs.isEmpty()) return null
        val nodes =
            specs.mapNotNull { spec ->
                val name = spec.substringBefore('=', "").ifEmpty { return@mapNotNull null }
                val rest = spec.substringAfter('=', "")
                val roots = rest.substringBefore(':').split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val deps = rest.substringAfter(':', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                ModuleNode(name = name, srcRoots = roots, dependsOn = deps)
            }
        return nodes.takeIf { it.isNotEmpty() }?.let { ModuleGraph(it) }
    }
}
