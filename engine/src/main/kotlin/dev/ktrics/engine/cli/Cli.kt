package dev.ktrics.engine.cli

import java.io.File

/** Everything a command handler needs: parsed tail args, the project root, the relayed env, the sink. */
data class CommandContext(
    val args: List<String>,
    val projectRoot: File,
    val env: Map<String, String>,
    val sink: CommandSink,
    /**
     * The client's working directory, relayed over the socket. Relative file arguments (`--output`,
     * `--input`, `--coverage`) MUST resolve here — resolving them against the daemon's own CWD (its
     * spawn directory) would read/write the wrong files. Defaults to [projectRoot] for foreground use.
     */
    val cwd: File = projectRoot,
) {
    /** Resolves a possibly-relative path against the client's [cwd], not the daemon's process CWD. */
    fun resolvePath(path: String): File = File(path).let { if (it.isAbsolute) it else File(cwd, path) }

    /** First positional (non-flag) argument, if any. */
    fun firstPositional(): String? = positionals().firstOrNull()

    /**
     * Positional (non-option) arguments, in order, with value-option values removed. A naive
     * "first token not starting with `-`" picks up an option's value — `analyze --reporter ai src/`
     * would treat `ai` as the target — so we walk the tokens and skip the value a known value-option
     * consumes. Both option forms are handled: `--name=value` is one self-contained token (dropped on
     * its own), while `--name value` consumes the following token. `--` ends option parsing;
     * everything after it is positional.
     */
    fun positionals(): List<String> {
        val result = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--" -> {
                    result.addAll(args.subList(i + 1, args.size))
                    return result
                }
                // `--name=value`: a self-contained option token (its value is glued on). It carries its
                // own value, so it never consumes the next token — and is never a positional. A bare
                // `-1` is a negative number, not an option, so require a long-option `--` prefix.
                isInlineOption(a) -> i += 1
                a.length > 1 && a.startsWith("-") ->
                    // A value-option consumes the following token; a bare flag does not.
                    i += if (a in VALUE_OPTIONS && i + 1 < args.size) 2 else 1
                else -> {
                    result.add(a)
                    i++
                }
            }
        }
        return result
    }

    /**
     * Value of either option form: `--name=value` (the inline form, value after `=`) or the two-token
     * `--name value` form. The inline form is matched first; otherwise we fall back to the pair scan.
     */
    fun option(name: String): String? {
        val prefix = "$name="
        args.firstOrNull { it.startsWith(prefix) }?.let { return it.substring(prefix.length) }
        return args.zipWithNext().firstOrNull { it.first == name }?.second
    }

    /** Whether a bare flag `--name` is present. */
    fun flag(name: String): Boolean = name in args

    /**
     * True for a `--name=value` token: a long option (`--` prefix) carrying its value inline. A bare
     * negative number like `-1` is excluded by the `--` requirement, and the `=` must follow the name
     * (not be the very first char) so `--=x` isn't treated as an option.
     */
    private fun isInlineOption(token: String): Boolean = token.startsWith("--") && token.indexOf('=') > 2

    companion object {
        /**
         * Options that consume a following value token (`--name value`). Kept here so positional
         * parsing knows which tokens are option values rather than targets. Bare flags
         * (`--strict-dismiss`, `--fatal-warnings`, …) are deliberately absent.
         */
        val VALUE_OPTIONS: Set<String> =
            setOf(
                "--reporter", "--output", "--coverage", "--since", "--snapshot", "--limit",
                "--input", "--config", "--module", "--before", "--after", "--root",
                "--depth", "--direction", "--filter", "--metric", "--concurrency",
            )
    }
}

/** A single CLI command (analyze, rules, …). Returns a sysexits code. */
fun interface CommandHandler {
    fun run(ctx: CommandContext): Int
}

/**
 * The engine-side command facade. The daemon's router handles the version handshake and
 * daemon lifecycle, then delegates everything else here. Handlers are registered as each
 * capability lands (rules first, analyze once the pipeline is built, …) so an unimplemented command
 * reports honestly instead of silently succeeding — an agent must never read a no-op as a clean run.
 */
class Cli(private val handlers: Map<String, CommandHandler>) {
    fun run(
        command: String,
        ctx: CommandContext,
    ): Int {
        val handler = handlers[command]
        if (handler == null) {
            ctx.sink.errLine("ktrics: command '$command' is not available in this build.")
            return Exit.USAGE
        }
        return runCatching { handler.run(ctx) }.getOrElse { error ->
            ctx.sink.errLine("ktrics: internal error in '$command': ${error.message}")
            Exit.INTERNAL
        }
    }

    fun supports(command: String): Boolean = command in handlers

    companion object {
        /**
         * The default command set. Populated incrementally as phases land; the registry is the
         * single place that records which commands exist.
         */
        fun default(): Cli = Cli(buildMap { CommandRegistry.registerAll(this) })
    }
}

/**
 * Registration seam. Each capability adds its handlers here (kept in one file so the wired-up surface is
 * auditable at a glance). Initial wiring is nothing engine-heavy; the router still serves version and
 * daemon lifecycle so the client↔daemon loop is demonstrable.
 */
object CommandRegistry {
    fun registerAll(into: MutableMap<String, CommandHandler>) {
        // Catalogue, reporters, embedded docs.
        into["analyze"] = dev.ktrics.engine.cli.commands.AnalyzeCommand
        into["report"] = dev.ktrics.engine.cli.commands.ReportCommand
        into["rules"] = dev.ktrics.engine.cli.commands.RulesCommand
        into["explain"] = dev.ktrics.engine.cli.commands.ExplainCommand
        into["inspect"] = dev.ktrics.engine.cli.commands.InspectCommand
        into["manual"] = dev.ktrics.engine.cli.commands.DocCommand.MANUAL
        into["ai-loop"] = dev.ktrics.engine.cli.commands.DocCommand.AI_LOOP
        // Config doctor.
        into["doctor"] = dev.ktrics.engine.cli.commands.DoctorCommand
        // AI-loop regression.
        into["regression"] = dev.ktrics.engine.cli.commands.RegressionCommand
        // Public-API reachability.
        into["unused"] = dev.ktrics.engine.cli.commands.UnusedCommand
    }
}
