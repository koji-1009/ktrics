package dev.ktrics.daemon

import java.io.File
import kotlin.system.exitProcess

/**
 * ktricsd entry point. Two modes:
 *   --serve --root <path>   run the warm socket server (auto-spawned by the client).
 *   <command> …             one-shot foreground run (the `--no-daemon` path; also handy for tests).
 *
 * Thin by design: the one-shot logic lives in [DaemonCli] (unit-tested); this shell only owns the
 * blocking `--serve` loop and the `exitProcess` boundary, so it is excluded from coverage.
 */
fun main(args: Array<String>) {
    when {
        args.firstOrNull() == "--serve" -> {
            val root =
                DaemonCli.optionValue(args.toList(), "--root")?.let { File(it) }
                    ?: File(System.getProperty("user.dir"))
            SocketServer(root.absoluteFile.normalize()).serve()
        }
        else -> exitProcess(DaemonCli.runForeground(args.toList()))
    }
}
