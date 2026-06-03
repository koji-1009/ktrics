package dev.ktrics.engine.cli

/**
 * sysexits exit codes. Mirrors `dev.ktrics.client.proto.ExitCode` by value — the numbers
 * are a fixed standard, so the daemon forwards an engine code to the client without translation.
 */
object Exit {
    const val OK = 0
    const val VIOLATIONS = 1
    const val USAGE = 64
    const val BAD_INPUT = 65
    const val INTERNAL = 70
    const val BAD_CONFIG = 78
}
