package dev.ktrics.client

import dev.ktrics.client.proto.ClientRequest
import dev.ktrics.client.proto.DaemonFrame
import dev.ktrics.client.proto.ExitCode
import dev.ktrics.client.proto.FrameCodec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.channels.Channels
import kotlin.io.path.createTempDirectory

/** Relay over a real unix socket: frame dispatch + the unreachable / mid-stream-death paths. */
class DaemonClientTest {
    private val request = ClientRequest(argv = listOf("--version"), cwd = "/x")

    private fun socketIn(dir: File) = File(dir, "d.sock")

    /** Drains the relayed request, then runs [send] to write the daemon's frames. */
    private fun server(
        path: File,
        send: (DataOutputStream) -> Unit,
    ): AutoCloseable =
        TestUnixServer.start(path) { ch ->
            val input = DataInputStream(Channels.newInputStream(ch).buffered())
            FrameCodec.readRequest(input) // consume the client's request
            send(DataOutputStream(Channels.newOutputStream(ch).buffered()))
        }

    @Test
    fun `an unreachable socket yields Unreachable`() {
        DaemonClient(File("/no/such/path/d.sock")).relay(request) shouldBe DaemonClient.Outcome.Unreachable
    }

    @Test
    fun `stdout and stderr frames are forwarded and Exit ends the relay`() {
        val dir = createTempDirectory("dc").toFile()
        val path = socketIn(dir)
        val srv =
            server(path) { out ->
                FrameCodec.writeFrame(out, DaemonFrame.Stdout("hello"))
                FrameCodec.writeFrame(out, DaemonFrame.Stderr("warn"))
                FrameCodec.writeFrame(out, DaemonFrame.Exit(7))
            }
        try {
            DaemonClient(path).relay(request) shouldBe DaemonClient.Outcome.Exited(7)
        } finally {
            srv.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a version mismatch frame is surfaced as VersionMismatch`() {
        val dir = createTempDirectory("dc").toFile()
        val path = socketIn(dir)
        val srv = server(path) { out -> FrameCodec.writeFrame(out, DaemonFrame.VersionMismatch(9, 1)) }
        try {
            val outcome = DaemonClient(path).relay(request)
            outcome.shouldBeInstanceOf<DaemonClient.Outcome.VersionMismatch>()
            outcome.daemonVersion shouldBe 9
        } finally {
            srv.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a daemon dying mid-stream is reported as an internal exit`() {
        val dir = createTempDirectory("dc").toFile()
        val path = socketIn(dir)
        // Read the request, then close without an Exit frame → the client's readFrame hits EOF.
        val srv =
            TestUnixServer.start(path) { ch ->
                FrameCodec.readRequest(DataInputStream(Channels.newInputStream(ch).buffered()))
                ch.close()
            }
        try {
            DaemonClient(path).relay(request) shouldBe DaemonClient.Outcome.Exited(ExitCode.INTERNAL.code)
        } finally {
            srv.close()
            dir.deleteRecursively()
        }
    }
}
