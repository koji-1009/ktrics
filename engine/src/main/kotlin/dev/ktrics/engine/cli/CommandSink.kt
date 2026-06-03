package dev.ktrics.engine.cli

/**
 * Where a command writes its output, abstracted from transport. The daemon backs it with a
 * socket sink; a `--no-daemon` cold run backs it with the process streams. Command implementations
 * are oblivious to which.
 */
interface CommandSink {
    fun out(text: String)

    fun err(text: String)

    fun outLine(text: String = "") = out(text + "\n")

    fun errLine(text: String = "") = err(text + "\n")
}
