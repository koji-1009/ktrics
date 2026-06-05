package dev.ktrics.daemon

import dev.ktrics.client.proto.ClientRequest
import dev.ktrics.client.proto.DaemonEndpoint
import dev.ktrics.client.proto.FrameCodec
import dev.ktrics.client.proto.Protocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The warm daemon's socket server. Binds the per-project unix socket, accepts client
 * connections, reads one [ClientRequest], runs it through the [CommandRouter], and frames the output
 * back. Self-terminates after [idleTimeoutMs] with no work (idle self-termination) and enforces the client/daemon
 * version handshake — on mismatch it tells the client to respawn rather than serve stale logic.
 *
 * The transport is wired end-to-end. Future enhancements include warm session, snapshot cache keyed by
 * project root + module-graph + classpath + config hash, and AppCDS.
 */
class SocketServer(
    private val projectRoot: File,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    /** How often the idle watchdog polls; injectable so the self-termination path is testable in millis. */
    private val idleCheckIntervalMs: Long = IDLE_CHECK_INTERVAL_MS,
    /** The blocking accept, injectable so the accept-error backoff is testable without forcing a real fd failure. */
    private val acceptConnection: (ServerSocketChannel) -> SocketChannel = { it.accept() },
) : CommandRouter.DaemonControl {
    private val socketPath = DaemonEndpoint.socketPath(projectRoot)
    private val pidFile = DaemonEndpoint.pidFile(projectRoot)
    private val router = CommandRouter(this)
    private val workers = Executors.newCachedThreadPool { r -> thread(start = false, isDaemon = true) { r.run() } }
    private val lastActivity = AtomicLong(System.nanoTime())
    private val activeRequests = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var running = true

    @Volatile private var server: ServerSocketChannel? = null

    /** Guards [cleanup] so it runs exactly once across serve-end and the shutdown hook (see cleanup). */
    private val cleanedUp = java.util.concurrent.atomic.AtomicBoolean(false)

    fun serve() {
        // If a live daemon already owns this socket, it wins: abort our startup cleanly so the launcher
        // connects to the incumbent rather than orphaning it (see cleanStaleSocket).
        if (!cleanStaleSocket()) return
        socketPath.parentFile.mkdirs()
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.bind(UnixDomainSocketAddress.of(socketPath.toPath()))
        } catch (_: java.io.IOException) {
            // Two fresh daemons can both pass the cleanStaleSocket probe before either binds; the
            // loser's bind throws. The incumbent serves the client — exit cleanly, not with a stack
            // trace, and leave the winner's socket/pid files alone.
            runCatching { channel.close() }
            return
        }
        server = channel
        writePidFile()
        Runtime.getRuntime().addShutdownHook(Thread(::cleanup))
        startIdleWatchdog()

        while (running) {
            val conn =
                try {
                    acceptConnection(channel)
                } catch (_: Exception) {
                    if (!running) break
                    // A persistent accept() failure (e.g. fd exhaustion) would otherwise hot-spin at
                    // 100% CPU; back off briefly so a recurring error can't busy-loop.
                    Thread.sleep(ACCEPT_ERROR_BACKOFF_MS)
                    continue
                }
            workers.submit { handle(conn) }
        }
        cleanup()
    }

    // A broad catch is deliberate here: one malformed/aborted client request must never bring the
    // warm daemon down (see the catch below). The generic-exception warning is suppressed for that reason.
    @Suppress("TooGenericExceptionCaught")
    private fun handle(conn: SocketChannel) {
        activeRequests.incrementAndGet()
        try {
            conn.use { ch ->
                val input = DataInputStream(Channels.newInputStream(ch).buffered())
                val out = DataOutputStream(Channels.newOutputStream(ch).buffered())
                val request = FrameCodec.readRequest(input)
                val sink = SocketSink(out)
                // Version handshake: refuse a client speaking a different protocol.
                if (request.protocolVersion != Protocol.VERSION) {
                    sink.versionMismatch(Protocol.VERSION, request.protocolVersion)
                    return
                }
                val code = router.dispatch(request.argv, request.cwd, request.env, sink)
                sink.exit(code)
            }
        } catch (e: Exception) {
            // A broken connection shouldn't take the daemon down; log to stderr of the daemon process.
            System.err.println("ktricsd: request failed: ${e.message}")
        } finally {
            lastActivity.set(System.nanoTime())
            activeRequests.decrementAndGet()
        }
    }

    private fun startIdleWatchdog() {
        thread(isDaemon = true, name = "ktricsd-idle") {
            while (running) {
                Thread.sleep(idleCheckIntervalMs)
                val idleNs = System.nanoTime() - lastActivity.get()
                if (activeRequests.get() == 0 && idleNs > idleTimeoutMs * 1_000_000) {
                    requestShutdown()
                }
            }
        }
    }

    override fun requestShutdown() {
        running = false
        runCatching { server?.close() }
    }

    override fun status(): String =
        buildString {
            appendLine("ktricsd ${BuildInfo.VERSION} (protocol v${Protocol.VERSION})")
            appendLine("  root:   ${projectRoot.absolutePath}")
            appendLine("  socket: ${socketPath.absolutePath}")
            appendLine("  pid:    ${ProcessHandle.current().pid()}")
            appendLine("  active: ${activeRequests.get()} request(s)")
            append("  idle timeout: ${idleTimeoutMs / 1000}s")
        }

    /**
     * Prepares the socket path for binding. Returns true if startup may proceed, false if a *live*
     * daemon is already listening and we must abort (the incumbent wins). The file existing is not
     * enough: we probe for a live listener before deleting, so two daemons racing for the same project
     * can't have the loser unlink the winner's socket and orphan it. A truly stale file (no listener)
     * is deleted so we can rebind.
     */
    private fun cleanStaleSocket(): Boolean {
        if (!socketPath.exists()) return true
        if (isSocketLive(socketPath)) return false
        socketPath.delete()
        return true
    }

    /** Probes for a live listener by attempting the same client connect; true only if one answers. */
    private fun isSocketLive(socket: File): Boolean =
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use {
                it.connect(UnixDomainSocketAddress.of(socket.toPath()))
                true
            }
        } catch (_: Exception) {
            false
        }

    private fun writePidFile() {
        pidFile.writeText(ProcessHandle.current().pid().toString())
    }

    private fun cleanup() {
        // Run once: cleanup() fires both at serve()'s end and from the shutdown hook. Without this guard
        // a late second delete from a dying daemon could unlink a freshly-respawned daemon's socket/pid.
        if (!cleanedUp.compareAndSet(false, true)) return
        runCatching { socketPath.delete() }
        runCatching { pidFile.delete() }
        // Release the warm sessions (each holds the IntelliJ application disposable).
        runCatching { dev.ktrics.engine.WarmIndexCache.clear() }
        workers.shutdownNow()
    }

    companion object {
        /** Idle shutdown after this long with no requests (persistent-mode happy path). */
        const val DEFAULT_IDLE_TIMEOUT_MS: Long = 30 * 60 * 1000
        private const val IDLE_CHECK_INTERVAL_MS: Long = 5_000

        /** Backoff after a caught accept() error so a persistent failure can't hot-spin the loop. */
        private const val ACCEPT_ERROR_BACKOFF_MS: Long = 50
    }
}
