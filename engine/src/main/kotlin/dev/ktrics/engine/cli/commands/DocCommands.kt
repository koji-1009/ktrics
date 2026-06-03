package dev.ktrics.engine.cli.commands

import dev.ktrics.engine.cli.CommandContext
import dev.ktrics.engine.cli.CommandHandler
import dev.ktrics.engine.cli.Exit

/**
 * `ktrics manual` / `ktrics ai-loop` — print the embedded operator manual and loop walkthrough.
 * The docs are Kotlin resources baked into the binary, so they ship with the tool and
 * can never drift from the version in use.
 */
class DocCommand(private val resource: String, private val title: String) : CommandHandler {
    override fun run(ctx: CommandContext): Int {
        val text = javaClass.getResourceAsStream(resource)?.bufferedReader()?.use { it.readText() }
        if (text == null) {
            ctx.sink.errLine("ktrics: embedded doc '$title' is missing from this build.")
            return Exit.INTERNAL
        }
        ctx.sink.out(text)
        if (!text.endsWith("\n")) ctx.sink.out("\n")
        return Exit.OK
    }

    companion object {
        // doc/*.md are embedded at the resource root via the engine's resources.srcDir(rootProject/doc).
        val MANUAL = DocCommand("/manual.md", "manual")
        val AI_LOOP = DocCommand("/ai-loop.md", "ai-loop")
    }
}
