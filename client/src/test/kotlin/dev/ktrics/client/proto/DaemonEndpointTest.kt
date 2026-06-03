package dev.ktrics.client.proto

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import java.io.File

/** Deterministic, normalized socket/pid path derivation shared by client and daemon. */
class DaemonEndpointTest {
    @Test
    fun `socket path is deterministic for the same project root`() {
        val root = File("/work/project")
        DaemonEndpoint.socketPath(root) shouldBe DaemonEndpoint.socketPath(root)
    }

    @Test
    fun `the project root is normalized before hashing`() {
        // `/work/project` and `/work/other/../project` are the same root, so the same socket.
        DaemonEndpoint.socketPath(File("/work/other/../project")) shouldBe
            DaemonEndpoint.socketPath(File("/work/project"))
    }

    @Test
    fun `different roots map to different sockets`() {
        DaemonEndpoint.socketPath(File("/work/a")) shouldNotBe DaemonEndpoint.socketPath(File("/work/b"))
    }

    @Test
    fun `socket and pid files differ but share the runtime dir`() {
        val root = File("/work/project")
        val socket = DaemonEndpoint.socketPath(root)
        val pid = DaemonEndpoint.pidFile(root)
        socket shouldNotBe pid
        socket.parentFile shouldBe pid.parentFile
        socket.name shouldEndWith ".sock"
        pid.name shouldEndWith ".pid"
    }

    @Test
    fun `runtime dir exists under the ktrics namespace`() {
        val dir = DaemonEndpoint.runtimeDir()
        dir.name shouldBe "ktrics"
        dir.isDirectory shouldBe true // runtimeDir() mkdirs() on access
    }

    @Test
    fun `runtimeBase resolves env precedence XDG over KTRICS over home, ignoring blanks`() {
        DaemonEndpoint.runtimeBase(mapOf("XDG_RUNTIME_DIR" to "/xdg", "KTRICS_RUNTIME_DIR" to "/k"), "/home") shouldBe "/xdg"
        DaemonEndpoint.runtimeBase(mapOf("KTRICS_RUNTIME_DIR" to "/k"), "/home") shouldBe "/k"
        DaemonEndpoint.runtimeBase(emptyMap(), "/home") shouldBe "/home" + File.separator + ".ktrics"
        // a blank value is ignored and falls through to the next source.
        DaemonEndpoint.runtimeBase(mapOf("XDG_RUNTIME_DIR" to "   "), "/home") shouldBe "/home" + File.separator + ".ktrics"
    }

    @Test
    fun `the socket file name is the same short hash for socket and pid of one root`() {
        val root = File("/work/project")
        val socketStem = DaemonEndpoint.socketPath(root).name.removeSuffix(".sock")
        val pidStem = DaemonEndpoint.pidFile(root).name.removeSuffix(".pid")
        socketStem shouldBe pidStem
    }
}
