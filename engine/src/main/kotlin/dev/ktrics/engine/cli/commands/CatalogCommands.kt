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
