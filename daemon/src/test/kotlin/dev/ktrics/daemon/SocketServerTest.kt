package dev.ktrics.daemon

import dev.ktrics.client.proto.ClientRequest
import dev.ktrics.client.proto.DaemonEndpoint
import dev.ktrics.client.proto.DaemonFrame
import dev.ktrics.client.proto.FrameCodec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory

/**
 * End-to-end transport: a real [SocketServer] over a unix-domain socket, driven by a hand-rolled client
 * that frames a [ClientRequest] and reads [DaemonFrame]s back. Proves the accept → read →
 * dispatch → frame → exit loop and the version handshake on the wire.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocketServerTest {
    private lateinit var projectRoot: java.io.File
    private lateinit var server: SocketServer
    private lateinit var serverThread: Thread

    @BeforeAll
    fun setUp() {
        projectRoot = createTempDirectory("socketsrv").toFile()
        server = SocketServer(projectRoot, idleTimeoutMs = 60_000)
        serverThread = thread(isDaemon = true) { server.serve() }
        // Wait for the socket to be bound before any client connects.
        val socket = DaemonEndpoint.socketPath(projectRoot)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!socket.exists() && System.nanoTime() < deadline) Thread.sleep(20)
    }

    @AfterAll
    fun tearDown() {
        server.requestShutdown()
        serverThread.join(5_000)
    }

    @Test
    fun `the daemon self-terminates after the idle timeout elapses`() {
        val root = createTempDirectory("idle").toFile()
        try {
            // A tiny idle window + poll interval: the watchdog should shut the server down within millis,
            // so serve() returns and the thread dies (idle self-termination).
            val idleServer = SocketServer(root, idleTimeoutMs = 10, idleCheckIntervalMs = 20)
            val t = thread(isDaemon = true) { idleServer.serve() }
            t.join(3_000)
            t.isAlive shouldBe false
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a malformed request is caught and does not bring the server down`() {
        val address = UnixDomainSocketAddress.of(DaemonEndpoint.socketPath(projectRoot).toPath())
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(address)
            ch.write(java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0))) // not even a full 4-byte length prefix
        }
        // handle()'s catch logged and closed that connection; a fresh valid request still gets served.
        roundTrip(ClientRequest(argv = listOf("--version"), cwd = ".")).any { it is DaemonFrame.Exit } shouldBe true
    }

    @Test
    fun `a transient accept error backs off, then a shutdown ends the loop`() {
        val root = createTempDirectory("accept-err").toFile()
        try {
            // First accept() throws with running still true → the loop must back off and retry (not die, not
            // hot-spin). The second call flips running=false and throws again, so the catch breaks cleanly.
            val firstCall = java.util.concurrent.atomic.AtomicBoolean(true)
            lateinit var server: SocketServer
            server =
                SocketServer(
                    root,
                    idleTimeoutMs = 60_000,
                    acceptConnection = {
                        if (firstCall.getAndSet(false)) {
                            throw java.io.IOException("transient accept failure")
                        }
                        server.requestShutdown()
                        throw java.nio.channels.ClosedChannelException()
                    },
                )
            val t = thread(isDaemon = true) { server.serve() }
            t.join(3_000)
            t.isAlive shouldBe false
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a stale socket file with no listener is cleaned so the daemon can rebind`() {
        val root = createTempDirectory("stale").toFile()
        try {
            // Leave a regular file where the socket belongs: it exists but nothing is listening, so the
            // live-probe must fail and cleanStaleSocket must delete it before bind (a truly stale endpoint).
            val socket = DaemonEndpoint.socketPath(root)
            socket.parentFile.mkdirs()
            socket.writeText("leftover")
            val staleServer = SocketServer(root, idleTimeoutMs = 60_000)
            val t = thread(isDaemon = true) { staleServer.serve() }
            try {
                // The leftover file already makes socket.exists() true, so poll for a *live listener*
                // (a connect that succeeds) instead — that only happens once serve() deletes it and rebinds.
                val address = UnixDomainSocketAddress.of(socket.toPath())
                val deadline = System.nanoTime() + 5_000_000_000L
                var bound = false
                while (!bound && System.nanoTime() < deadline) {
                    try {
                        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                            ch.connect(address)
                            bound = true
                        }
                    } catch (_: Exception) {
                        Thread.sleep(20)
                    }
                }
                bound shouldBe true
                // A real round-trip proves the daemon rebound the socket rather than choking on the leftover.
                roundTrip(ClientRequest(argv = listOf("--version"), cwd = root.path), root)
                    .any { it is DaemonFrame.Exit } shouldBe true
            } finally {
                staleServer.requestShutdown()
                t.join(5_000)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a live incumbent makes a second daemon on the same root abort startup`() {
        // projectRoot already has a live server (setUp). A second server on the same root must detect the
        // live listener via the connect-probe and abort serve() without unlinking the incumbent's socket.
        val second = SocketServer(projectRoot, idleTimeoutMs = 60_000)
        val t = thread(isDaemon = true) { second.serve() }
        t.join(2_000)
        t.isAlive shouldBe false // serve() returned early: cleanStaleSocket() saw a live listener and bailed.
        // The incumbent is untouched and still serving.
        roundTrip(ClientRequest(argv = listOf("--version"), cwd = projectRoot.path))
            .any { it is DaemonFrame.Exit } shouldBe true
    }

    /** Sends one request over a fresh connection and collects the frames up to (and including) Exit. */
    private fun roundTrip(
        request: ClientRequest,
        root: java.io.File = projectRoot,
    ): List<DaemonFrame> {
        val address = UnixDomainSocketAddress.of(DaemonEndpoint.socketPath(root).toPath())
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(address)
            val out = DataOutputStream(Channels.newOutputStream(ch).buffered())
            val input = DataInputStream(Channels.newInputStream(ch).buffered())
            FrameCodec.writeRequest(out, request)
            val frames = ArrayList<DaemonFrame>()
            // Read until a terminal frame: Exit on the normal path, or VersionMismatch (after which the
            // server closes the connection without an Exit). An EOF means the server closed early.
            try {
                while (true) {
                    val frame = FrameCodec.readFrame(input)
                    frames.add(frame)
                    if (frame is DaemonFrame.Exit || frame is DaemonFrame.VersionMismatch) break
                }
            } catch (_: java.io.EOFException) {
                // connection closed; return whatever frames arrived
            }
            return frames
        }
    }

    @Test
    fun `a version request streams stdout then a zero exit`() {
        val frames = roundTrip(ClientRequest(argv = listOf("--version"), cwd = projectRoot.path))
        val stdout = frames.filterIsInstance<DaemonFrame.Stdout>().joinToString("") { it.data }
        stdout shouldContain "protocol v"
        (frames.last() as DaemonFrame.Exit).code shouldBe 0
    }

    @Test
    fun `an unknown command exits with the usage code on stderr`() {
        val frames = roundTrip(ClientRequest(argv = listOf("frobnicate"), cwd = projectRoot.path))
        val stderr = frames.filterIsInstance<DaemonFrame.Stderr>().joinToString("") { it.data }
        stderr shouldContain "unknown command"
        (frames.last() as DaemonFrame.Exit).code shouldBe 64
    }

    @Test
    fun `a protocol mismatch yields a VersionMismatch frame`() {
        val frames = roundTrip(ClientRequest(protocolVersion = 999, argv = listOf("--version"), cwd = projectRoot.path))
        val mismatch = frames.filterIsInstance<DaemonFrame.VersionMismatch>().single()
        mismatch.expected shouldBe 999
    }

    @Test
    fun `daemon status round-trips the control surface`() {
        val frames = roundTrip(ClientRequest(argv = listOf("daemon", "status"), cwd = projectRoot.path))
        val stdout = frames.filterIsInstance<DaemonFrame.Stdout>().joinToString("") { it.data }
        stdout shouldContain "ktricsd"
        (frames.last() as DaemonFrame.Exit).code shouldBe 0
    }
}
