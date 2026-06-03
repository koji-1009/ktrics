package dev.ktrics.daemon

import dev.ktrics.client.proto.DaemonFrame
import dev.ktrics.client.proto.FrameCodec
import dev.ktrics.engine.cli.CommandSink
import java.io.DataOutputStream

/**
 * Transport-specific [CommandSink] implementations. Command handlers (in :engine) write to the
 * generic interface; only these classes know whether output goes over the socket or to the streams.
 */

/** Frames output back to the client over the socket (only the report crosses the wire). */
class SocketSink(private val stream: DataOutputStream) : CommandSink {
    @Synchronized
    override fun out(text: String) {
        if (text.isNotEmpty()) FrameCodec.writeFrame(stream, DaemonFrame.Stdout(text))
    }

    @Synchronized
    override fun err(text: String) {
        if (text.isNotEmpty()) FrameCodec.writeFrame(stream, DaemonFrame.Stderr(text))
    }

    @Synchronized
    fun exit(code: Int) {
        FrameCodec.writeFrame(stream, DaemonFrame.Exit(code))
    }

    @Synchronized
    fun versionMismatch(
        daemonVersion: Int,
        expected: Int,
    ) {
        FrameCodec.writeFrame(stream, DaemonFrame.VersionMismatch(daemonVersion, expected))
    }
}

/** Writes straight to the process streams; used by the `--no-daemon` foreground one-shot run. */
class StreamSink : CommandSink {
    override fun out(text: String) = kotlin.io.print(text)

    override fun err(text: String) = System.err.print(text)
}
