package dev.ktrics.client

import dev.ktrics.client.proto.ClientRequest
import dev.ktrics.client.proto.DaemonFrame
import dev.ktrics.client.proto.ExitCode
import dev.ktrics.client.proto.FrameCodec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

/**
 * Opens the socket to a running daemon, relays one [ClientRequest], and streams [DaemonFrame]s back
 * to this process's stdout/stderr until [DaemonFrame.Exit]. Links none of the platform.
 */
class DaemonClient(private val socketPath: File) {
    /** Outcome of a relay attempt: either an exit code, or a signal that the daemon must be respawned. */
    sealed interface Outcome {
        data class Exited(val code: Int) : Outcome

        data class VersionMismatch(val daemonVersion: Int) : Outcome

        data object Unreachable : Outcome
    }

    fun relay(request: ClientRequest): Outcome {
        val address = UnixDomainSocketAddress.of(socketPath.toPath())
        val channel =
            try {
                SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(address) }
            } catch (_: IOException) {
                return Outcome.Unreachable
            }
        channel.use { ch ->
            val out = DataOutputStream(Channels.newOutputStream(ch).buffered())
            val input = DataInputStream(Channels.newInputStream(ch).buffered())
            FrameCodec.writeRequest(out, request)
            while (true) {
                val frame =
                    try {
                        FrameCodec.readFrame(input)
                    } catch (_: Exception) {
                        // Daemon died mid-stream, or the frame was malformed/desynced (a bad length
                        // prefix throws IllegalArgumentException, malformed JSON SerializationException);
                        // treat any of these as internal so the caller surfaces it instead of crashing.
                        return Outcome.Exited(ExitCode.INTERNAL.code)
                    }
                when (frame) {
                    is DaemonFrame.Stdout -> System.out.print(frame.data).also { System.out.flush() }
                    is DaemonFrame.Stderr -> System.err.print(frame.data).also { System.err.flush() }
                    is DaemonFrame.VersionMismatch -> return Outcome.VersionMismatch(frame.daemonVersion)
                    is DaemonFrame.Exit -> return Outcome.Exited(frame.code)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            return Outcome.Exited(ExitCode.INTERNAL.code)
        }
    }
}
