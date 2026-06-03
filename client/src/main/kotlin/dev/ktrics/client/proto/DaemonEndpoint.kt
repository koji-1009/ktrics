package dev.ktrics.client.proto

import java.io.File
import java.security.MessageDigest

/**
 * Where the daemon listens, derived deterministically from the project root so client and daemon
 * agree without negotiation. The daemon keys its in-memory session by project root + module-graph +
 * classpath + config hash so differing invocations never share an index. The socket path encodes only
 * the project root; the daemon further partitions its in-memory sessions by the full key.
 */
object DaemonEndpoint {
    /** Per-user runtime dir holding daemon sockets and pid files. */
    fun runtimeDir(): File = File(runtimeBase(System.getenv(), System.getProperty("user.home")), "ktrics").also { it.mkdirs() }

    /** Pure precedence: `XDG_RUNTIME_DIR` → `KTRICS_RUNTIME_DIR` → `~/.ktrics`, blanks ignored. Seam for tests. */
    internal fun runtimeBase(
        env: Map<String, String>,
        home: String,
    ): String =
        env["XDG_RUNTIME_DIR"]?.takeIf { it.isNotBlank() }
            ?: env["KTRICS_RUNTIME_DIR"]?.takeIf { it.isNotBlank() }
            ?: (home + File.separator + ".ktrics")

    /** The unix-domain socket path for [projectRoot]. Same input → same path on both sides. */
    fun socketPath(projectRoot: File): File = File(runtimeDir(), "d-${shortHash(projectRoot.absoluteFile.normalize().path)}.sock")

    /** The pid file the daemon writes so the client can confirm liveness / send `daemon stop`. */
    fun pidFile(projectRoot: File): File = File(runtimeDir(), "d-${shortHash(projectRoot.absoluteFile.normalize().path)}.pid")

    private fun shortHash(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
