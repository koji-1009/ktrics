package dev.ktrics.client.proto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The client↔daemon wire protocol. Deliberately platform-free and tiny: NO AST crosses the
 * boundary — parse→metrics→report all happen daemon-side; the socket carries only the
 * request and a stream of output frames + exit code.
 *
 * Owned by :client (the module that links NONE of the platform); :daemon depends on :client purely
 * for these DTOs. The version handshake guards against client/daemon drift.
 */
object Protocol {
    /** Bumped only on a breaking wire change. The daemon restarts on mismatch rather than serve stale logic. */
    const val VERSION: Int = 1

    val json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "t"
        }
}

/** One request: the client relays argv + cwd + env; the daemon does everything else. */
@Serializable
data class ClientRequest(
    val protocolVersion: Int = Protocol.VERSION,
    val argv: List<String>,
    val cwd: String,
    val env: Map<String, String> = emptyMap(),
)

/** A streamed frame from daemon to client. The client writes one [ClientRequest], reads frames to [Exit]. */
@Serializable
sealed interface DaemonFrame {
    /** A chunk of standard output to forward verbatim. */
    @Serializable
    data class Stdout(val data: String) : DaemonFrame

    /** A chunk of standard error to forward verbatim. */
    @Serializable
    data class Stderr(val data: String) : DaemonFrame

    /** The daemon refused the request because protocol versions differ — client should respawn it. */
    @Serializable
    data class VersionMismatch(val daemonVersion: Int, val expected: Int) : DaemonFrame

    /** Terminal frame: the process exit code (sysexits). */
    @Serializable
    data class Exit(val code: Int) : DaemonFrame
}
