package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandHandler
import dev.ktrics.engine.cli.Exit
import dev.ktrics.report.CatalogRenderer

/** `ktrics rules` — the full metric catalogue with defaults and auto-explain summaries. */
object RulesCommand : CommandHandler {
    override fun run(ctx: CommandContext): Int {
        ctx.sink.out(CatalogRenderer.rules())
        return Exit.OK
    }
}

/** `ktrics explain <metric-id>` — the full per-metric auto-explain `rules` points at. */
object ExplainCommand : CommandHandler {
    override fun run(ctx: CommandContext): Int {
        val id = ctx.firstPositional()
        if (id == null) {
            ctx.sink.errLine("ktrics: usage: ktrics explain <metric-id>  (run `ktrics rules` for the catalogue)")
            return Exit.USAGE
        }
        val text = CatalogRenderer.explainMetric(id)
        if (text == null) {
            ctx.sink.errLine("ktrics: unknown metric '$id' (run `ktrics rules` for valid ids).")
            return Exit.USAGE
        }
        ctx.sink.out(text)
        return Exit.OK
    }
}
