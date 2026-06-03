package dev.ktrics.engine.cli.commands

import dev.ktrics.config.ConfigLoader
import dev.ktrics.config.Doctor
import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandHandler
import dev.ktrics.engine.cli.Exit

/** `ktrics doctor` — validate ktrics.yaml. Non-zero exit on any error diagnostic. */
object DoctorCommand : CommandHandler {
    override fun run(ctx: CommandContext): Int {
        val load = ConfigLoader.load(ctx.projectRoot, ctx.option("--config")?.let { ctx.resolvePath(it) })
        val diagnostics = Doctor.check(load)
        load.source?.let { ctx.sink.outLine("ktrics doctor: ${it.path}") }
        diagnostics.forEach { d ->
            val prefix =
                when (d.severity) {
                    Doctor.Severity.ERROR -> "error"
                    Doctor.Severity.WARNING -> "warn "
                    Doctor.Severity.INFO -> "info "
                }
            ctx.sink.outLine("  $prefix  ${d.message}")
        }
        return if (diagnostics.any { it.severity == Doctor.Severity.ERROR }) Exit.BAD_CONFIG else Exit.OK
    }
}
