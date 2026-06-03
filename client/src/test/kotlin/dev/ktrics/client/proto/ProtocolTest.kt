package dev.ktrics.client.proto

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test

/** Wire-protocol JSON: discriminated sealed frames + default-carrying requests. */
class ProtocolTest {
    private inline fun <reified T> roundTrip(value: T): T = Protocol.json.decodeFromString(Protocol.json.encodeToString(value))

    @Test
    fun `a ClientRequest round-trips with its defaults encoded`() {
        val request = ClientRequest(argv = listOf("rules"), cwd = "/here")
        val json = Protocol.json.encodeToString(request)
        // encodeDefaults is on, so the pinned protocol version travels on the wire.
        json shouldContain "\"protocolVersion\":${Protocol.VERSION}"
        roundTrip(request) shouldBe request
    }

    @Test
    fun `unknown keys are ignored so an older client tolerates a newer daemon field`() {
        val withExtra = """{"argv":["rules"],"cwd":"/here","futureField":true}"""
        val decoded = Protocol.json.decodeFromString<ClientRequest>(withExtra)
        decoded.argv shouldBe listOf("rules")
        decoded.cwd shouldBe "/here"
    }

    @Test
    fun `each DaemonFrame variant carries the t discriminator`() {
        Protocol.json.encodeToString<DaemonFrame>(DaemonFrame.Stdout("x")) shouldContain "\"t\":"
        roundTrip<DaemonFrame>(DaemonFrame.Stdout("x")) shouldBe DaemonFrame.Stdout("x")
        roundTrip<DaemonFrame>(DaemonFrame.Stderr("y")) shouldBe DaemonFrame.Stderr("y")
        roundTrip<DaemonFrame>(DaemonFrame.VersionMismatch(daemonVersion = 9, expected = 1)) shouldBe
            DaemonFrame.VersionMismatch(9, 1)
        roundTrip<DaemonFrame>(DaemonFrame.Exit(70)) shouldBe DaemonFrame.Exit(70)
    }

    @Test
    fun `the discriminator distinguishes Stdout from Stderr`() {
        val stdoutJson = Protocol.json.encodeToString<DaemonFrame>(DaemonFrame.Stdout("boom"))
        val stderrJson = Protocol.json.encodeToString<DaemonFrame>(DaemonFrame.Stderr("boom"))
        // Same payload field, different discriminator — so a round-trip re-selects the right variant.
        stdoutJson shouldContain "Stdout"
        stderrJson shouldContain "Stderr"
        (Protocol.json.decodeFromString<DaemonFrame>(stderrJson) as DaemonFrame.Stderr).data shouldBe "boom"
    }

    @Test
    fun `the protocol version is the pinned handshake constant`() {
        Protocol.VERSION shouldBe 1
    }
}
