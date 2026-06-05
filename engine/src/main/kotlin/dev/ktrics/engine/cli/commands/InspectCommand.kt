package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.GraphSource
import dev.ktrics.engine.ProjectInputs
import dev.ktrics.engine.WarmIndexCache
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandHandler
import dev.ktrics.engine.cli.Exit
import dev.ktrics.ir.CallGraphSignal
import dev.ktrics.unused.CallGraph
import dev.ktrics.unused.InspectionDirection
import dev.ktrics.unused.InspectionNode
import dev.ktrics.unused.InspectionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `ktrics inspect <symbol>` — walks the resolved call graph around a named declaration and prints its
 * upstream (callers) / downstream (callees) neighbourhood (sibling of dartrics' `inspect`). Reference
 * information, NOT a finding: it feeds the refactor / dismiss / punt decision with structure the
 * metric value alone doesn't carry. Reporters: `ai` (default) and `json` only — there is nothing to
 * render as md/sarif because the output carries no severity.
 */
object InspectCommand : CommandHandler {
    private data class Args(val symbol: String, val reporter: String, val depth: Int, val direction: InspectionDirection)

    override fun run(ctx: CommandContext): Int {
        val args = parseArgs(ctx) ?: return Exit.USAGE
        val resolved = GraphSource.resolve(ctx, ctx.projectRoot)
        if (!GraphSource.reportConfig(ctx, resolved)) return Exit.BAD_CONFIG
        // withWarm (not a bare get): the graph build traverses PSI under the cache monitor, so a
        // concurrent request cannot rebuild the cache and dispose the session mid-traversal.
        val graph =
            WarmIndexCache.withWarm(ctx.projectRoot, resolved.graph, resolved.configHash, resolved.resolved) { warm ->
                CallGraph.build(warm.units, ProjectInputs.classifierFor(ctx.projectRoot, resolved.resolved))
            }
        val result = graph.inspect(args.symbol, args.depth, args.direction)

        val text = if (args.reporter == "json") renderJson(result) else renderAi(result)
        val output = ctx.option("--output")
        if (output != null) {
            val file = ctx.resolvePath(output)
            // Create the parent dir so an `--output` into a not-yet-existing directory writes the file
            // instead of throwing FileNotFoundException → INTERNAL (70).
            file.parentFile?.mkdirs()
            file.writeText(text)
            ctx.sink.errLine("ktrics: wrote inspect report to $output")
        } else {
            ctx.sink.out(text)
            if (!text.endsWith("\n")) ctx.sink.out("\n")
        }
        return Exit.OK
    }

    /** Parses + validates the inspect arguments; emits a usage message and returns null on any error. */
    private fun parseArgs(ctx: CommandContext): Args? {
        val symbol = ctx.firstPositional() ?: return fail(ctx, "usage: ktrics inspect <symbol> [--depth N] [--direction up|down|both]")
        // Normalize like ReporterFormat.fromId (which lowercases) so `--reporter AI` is accepted.
        val reporter = (ctx.option("--reporter") ?: "ai").lowercase()
        if (reporter != "ai" && reporter != "json") return fail(ctx, "inspect supports only --reporter ai|json (got '$reporter').")
        val depth = parseDepth(ctx) ?: return null
        val direction = parseDirection(ctx) ?: return null
        return Args(symbol, reporter, depth, direction)
    }

    /** Returns the depth (default when absent), or null after emitting an error for a bad value. */
    private fun parseDepth(ctx: CommandContext): Int? {
        val arg = ctx.option("--depth") ?: return DEFAULT_DEPTH
        val n = arg.toIntOrNull()
        if (n == null || n < 1) {
            ctx.sink.errLine("ktrics: --depth must be a positive integer (got '$arg').")
            return null
        }
        return n
    }

    private fun parseDirection(ctx: CommandContext): InspectionDirection? =
        when (ctx.option("--direction")?.lowercase()) {
            null, "both" -> InspectionDirection.BOTH
            "up" -> InspectionDirection.UP
            "down" -> InspectionDirection.DOWN
            else -> {
                ctx.sink.errLine("ktrics: --direction must be up|down|both (got '${ctx.option("--direction")}').")
                null
            }
        }

    private fun fail(
        ctx: CommandContext,
        message: String,
    ): Args? {
        ctx.sink.errLine("ktrics: $message")
        return null
    }

    private fun renderAi(result: InspectionResult): String =
        buildString {
            appendLine("# ktrics inspect-report v1")
            appendLine("# Subgraph of the resolved call graph around the queried symbol.")
            appendLine("# Values are reference information — compare against intent, not a threshold.")
            appendLine("query: ${result.query}")
            appendLine("depth: ${result.depth}")
            appendLine("direction: ${result.direction.name.lowercase()}")
            if (result.matches.isEmpty()) {
                appendLine("matches: []")
                return@buildString
            }
            appendLine("matches:")
            for (match in result.matches) {
                appendLine("  - anchor:")
                appendAnchor(match.anchor)
                appendNodes("upstream", match.upstream)
                appendNodes("downstream", match.downstream)
            }
        }

    private fun StringBuilder.appendAnchor(s: CallGraphSignal) {
        appendLine("      file: ${s.file}")
        appendLine("      line: ${s.line}")
        appendLine("      scope: ${s.scopeName}")
        appendLine("      kind: ${s.kind}")
        appendLine("      fanInCallers: ${s.fanInCallers}")
        appendLine("      fanInCalls: ${s.fanInCalls}")
        appendLine("      fanOutCallees: ${s.fanOutCallees}")
        appendLine("      fanOutCalls: ${s.fanOutCalls}")
    }

    private fun StringBuilder.appendNodes(
        key: String,
        nodes: List<InspectionNode>,
    ) {
        if (nodes.isEmpty()) {
            appendLine("    $key: []")
            return
        }
        appendLine("    $key:")
        for (n in nodes) {
            appendLine("      - depth: ${n.depth}")
            appendLine("        incomingEdgeCount: ${n.incomingEdgeCount}")
            appendLine("        file: ${n.signal.file}")
            appendLine("        line: ${n.signal.line}")
            appendLine("        scope: ${n.signal.scopeName}")
            appendLine("        kind: ${n.signal.kind}")
            appendLine("        fanInCallers: ${n.signal.fanInCallers}")
            appendLine("        fanInCalls: ${n.signal.fanInCalls}")
            appendLine("        fanOutCallees: ${n.signal.fanOutCallees}")
            appendLine("        fanOutCalls: ${n.signal.fanOutCalls}")
        }
    }

    private fun renderJson(result: InspectionResult): String {
        val obj =
            buildJsonObject {
                put("query", result.query)
                put("depth", result.depth)
                put("direction", result.direction.name.lowercase())
                put(
                    "matches",
                    JsonArray(
                        result.matches.map { m ->
                            buildJsonObject {
                                put("anchor", signalJson(m.anchor))
                                put("upstream", nodesJson(m.upstream))
                                put("downstream", nodesJson(m.downstream))
                            }
                        },
                    ),
                )
            }
        return PRETTY.encodeToString(JsonObject.serializer(), obj)
    }

    private fun nodesJson(nodes: List<InspectionNode>): JsonArray =
        buildJsonArray {
            for (n in nodes) add(
                buildJsonObject {
                    put("depth", n.depth)
                    put("incomingEdgeCount", n.incomingEdgeCount)
                    put("signal", signalJson(n.signal))
                },
            )
        }

    private fun signalJson(s: CallGraphSignal): JsonObject =
        buildJsonObject {
            put("file", s.file)
            put("line", s.line)
            put("scope", s.scopeName)
            put("kind", s.kind)
            put("fanInCallers", s.fanInCallers)
            put("fanInCalls", s.fanInCalls)
            put("fanOutCallees", s.fanOutCallees)
            put("fanOutCalls", s.fanOutCalls)
        }

    private const val DEFAULT_DEPTH = 2
    private val PRETTY = Json { prettyPrint = true }
}
