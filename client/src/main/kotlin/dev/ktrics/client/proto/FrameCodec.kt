package dev.ktrics.client.proto

import kotlinx.serialization.encodeToString
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Length-prefixed JSON framing over the socket stream. Each frame is a 4-byte big-endian length
 * followed by that many UTF-8 bytes of JSON. Simple, language-neutral, and bounded so a runaway
 * daemon can't make the client allocate without limit.
 */
object FrameCodec {
    /** Refuse frames larger than this; a single report chunk is far smaller (no AST over wire). */
    const val MAX_FRAME_BYTES: Int = 64 * 1024 * 1024

    fun writeRequest(
        out: DataOutputStream,
        request: ClientRequest,
    ) {
        writeBytes(out, Protocol.json.encodeToString(request).toByteArray(Charsets.UTF_8))
    }

    fun readRequest(input: DataInputStream): ClientRequest = Protocol.json.decodeFromString(String(readBytes(input), Charsets.UTF_8))

    fun writeFrame(
        out: DataOutputStream,
        frame: DaemonFrame,
    ) {
        writeBytes(out, Protocol.json.encodeToString(frame).toByteArray(Charsets.UTF_8))
    }

    fun readFrame(input: DataInputStream): DaemonFrame = Protocol.json.decodeFromString(String(readBytes(input), Charsets.UTF_8))

    private fun writeBytes(
        out: DataOutputStream,
        bytes: ByteArray,
    ) {
        require(bytes.size <= MAX_FRAME_BYTES) { "frame too large: ${bytes.size} bytes" }
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val len = input.readInt()
        require(len in 0..MAX_FRAME_BYTES) { "frame length out of bounds: $len" }
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf
    }
}
