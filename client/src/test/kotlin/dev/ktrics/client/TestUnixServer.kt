package dev.ktrics.client

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread

/**
 * A throwaway unix-domain server socket for client tests — lets [DaemonClient]/[DaemonLauncher] talk to
 * a real socket without spawning a daemon. [handle] runs per accepted connection on a daemon thread.
 */
object TestUnixServer {
    fun start(
        path: File,
        handle: (SocketChannel) -> Unit = { it.close() },
    ): AutoCloseable {
        path.parentFile?.mkdirs()
        if (path.exists()) path.delete()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(path.toPath()))
        thread(isDaemon = true) {
            try {
                while (server.isOpen) {
                    val ch = server.accept()
                    thread(isDaemon = true) { runCatching { ch.use(handle) } }
                }
            } catch (_: Exception) {
                // server closed; stop accepting
            }
        }
        return AutoCloseable {
            runCatching { server.close() }
            runCatching { path.delete() }
        }
    }
}
