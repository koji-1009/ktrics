package dev.ktrics.client.proto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Length-prefixed framing: round-trip + bounds enforcement on untrusted input. */
class FrameCodecTest {
    private fun roundTripRequest(request: ClientRequest): ClientRequest {
        val buffer = ByteArrayOutputStream()
        FrameCodec.writeRequest(DataOutputStream(buffer), request)
        return FrameCodec.readRequest(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
    }

    private fun roundTripFrame(frame: DaemonFrame): DaemonFrame {
        val buffer = ByteArrayOutputStream()
        FrameCodec.writeFrame(DataOutputStream(buffer), frame)
        return FrameCodec.readFrame(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
    }

    /** A DataInputStream whose first frame declares [declaredLength] but carries no payload. */
    private fun streamWithLength(declaredLength: Int): DataInputStream {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(declaredLength)
        return DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
    }

    @Test
    fun `a request round-trips through the codec`() {
        val request =
            ClientRequest(
                argv = listOf("analyze", "--reporter", "ai", "src/"),
                cwd = "/work/project",
                env = mapOf("KTRICS_RUNTIME_DIR" to "/run"),
            )
        roundTripRequest(request) shouldBe request
    }

    @Test
    fun `every DaemonFrame variant round-trips`() {
        roundTripFrame(DaemonFrame.Stdout("hello")) shouldBe DaemonFrame.Stdout("hello")
        roundTripFrame(DaemonFrame.Stderr("oops")) shouldBe DaemonFrame.Stderr("oops")
        roundTripFrame(DaemonFrame.VersionMismatch(2, 1)) shouldBe DaemonFrame.VersionMismatch(2, 1)
        roundTripFrame(DaemonFrame.Exit(64)) shouldBe DaemonFrame.Exit(64)
    }

    @Test
    fun `the lower boundary length of zero passes the bounds check`() {
        // 0 is in 0..MAX, so the read advances past the length check; the failure (if any) comes
        // from JSON decoding empty bytes, never from an out-of-bounds rejection.
        val error = shouldThrow<Exception> { FrameCodec.readRequest(streamWithLength(0)) }
        (error.message ?: "") shouldNotContain "out of bounds"
    }

    @Test
    fun `a negative declared length is rejected as out of bounds`() {
        val error = shouldThrow<IllegalArgumentException> { FrameCodec.readRequest(streamWithLength(-1)) }
        error.message!! shouldContain "out of bounds"
    }

    @Test
    fun `a length above MAX_FRAME_BYTES is rejected as out of bounds`() {
        val error =
            shouldThrow<IllegalArgumentException> {
                FrameCodec.readRequest(streamWithLength(FrameCodec.MAX_FRAME_BYTES + 1))
            }
        error.message!! shouldContain "out of bounds"
    }

    @Test
    fun `the frame cap is the documented 64 MiB`() {
        FrameCodec.MAX_FRAME_BYTES shouldBe 64 * 1024 * 1024
    }

    // --- wire-format golden tests: a symmetric round-trip can't catch an endianness/charset break, so
    //     these pin the actual bytes a non-JVM client must produce/consume. ---

    @Test
    fun `the wire layout is a 4-byte big-endian length prefix then UTF-8 JSON`() {
        val buffer = ByteArrayOutputStream()
        FrameCodec.writeFrame(DataOutputStream(buffer), DaemonFrame.Exit(0))
        val bytes = buffer.toByteArray()

        // The 4-byte prefix is the payload length, BIG-endian: decode it by hand (high byte first).
        val declaredLen =
            ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        declaredLen shouldBe bytes.size - 4
        // Big-endian (not little-endian): a small length lands in the LAST prefix byte, so byte 0 is zero.
        bytes[0] shouldBe 0.toByte()
        // Payload is UTF-8 JSON with the discriminator key and the int field — not length-delimited binary.
        val payload = String(bytes.copyOfRange(4, bytes.size), Charsets.UTF_8)
        payload shouldContain "\"t\":"
        payload shouldContain "\"code\":0"
    }

    @Test
    fun `readFrame interprets a hand-built big-endian frame`() {
        // Take a real payload but re-frame it with a MANUALLY written big-endian length (independent of
        // DataOutputStream.writeInt) — proves the reader is big-endian, the byte contract a C/Rust client needs.
        val full = ByteArrayOutputStream().also { FrameCodec.writeFrame(DataOutputStream(it), DaemonFrame.Exit(42)) }.toByteArray()
        val payload = full.copyOfRange(4, full.size)
        val reframed = ByteArrayOutputStream()
        reframed.write((payload.size ushr 24) and 0xFF)
        reframed.write((payload.size ushr 16) and 0xFF)
        reframed.write((payload.size ushr 8) and 0xFF)
        reframed.write(payload.size and 0xFF)
        reframed.write(payload)
        FrameCodec.readFrame(DataInputStream(ByteArrayInputStream(reframed.toByteArray()))) shouldBe DaemonFrame.Exit(42)
    }

    @Test
    fun `a payload shorter than its declared length throws EOFException`() {
        // The single most likely real corruption: the daemon dies mid-frame.
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).apply {
            writeInt(100) // claim 100 bytes...
            write("only ten!!".toByteArray()) // ...but supply 10
        }
        shouldThrow<java.io.EOFException> {
            FrameCodec.readFrame(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
        }
    }

    @Test
    fun `an unknown frame discriminator is a decode failure, not a silent success`() {
        // A forward-incompatible daemon sending a new frame variant must fail predictably on an old client.
        val payload = """{"t":"FutureFrame","code":1}""".toByteArray(Charsets.UTF_8)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).apply {
            writeInt(payload.size)
            write(payload)
        }
        shouldThrow<Exception> {
            FrameCodec.readFrame(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
        }
    }
}
