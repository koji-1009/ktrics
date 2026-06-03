package dev.ktrics.client.proto

/**
 * Process exit codes, sysexits convention. The client returns the daemon's code verbatim;
 * the daemon maps engine outcomes onto these. Platform-free so both sides agree without the engine.
 */
enum class ExitCode(val code: Int) {
    /** Clean run; no violations (or violations without --fatal-warnings). */
    OK(0),

    /** Violations present and --fatal-warnings was set. */
    VIOLATIONS(1),

    /** Usage error: bad flags/arguments. */
    USAGE(64),

    /** Bad input: unreadable file, malformed report, unresolved git ref. */
    BAD_INPUT(65),

    /** Internal error: an unexpected failure in the analyzer. */
    INTERNAL(70),

    /** Bad config: ktrics.yaml failed schema validation. */
    BAD_CONFIG(78),
    ;

    companion object {
        fun of(code: Int): ExitCode? = entries.firstOrNull { it.code == code }
    }
}
