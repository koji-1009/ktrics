package dev.ktrics.client

import dev.ktrics.client.proto.ClientRequest
import dev.ktrics.client.proto.DaemonEndpoint
import dev.ktrics.client.proto.ExitCode
import dev.ktrics.client.proto.Protocol
import java.io.File

/**
 * The client's logic, separated from the JVM `main` shell so it is fully testable (DI seams for the
 * daemon launch/relay; pure helpers take their inputs explicitly). `main` in Main.kt is a one-liner
 * that calls [dispatch] and feeds the result to `exitProcess`.
 */
internal object ClientCli {
    /** Handles the local fast paths (`--version`), else assembles the request and relays it via [runner]. */
    fun dispatch(
        args: List<String>,
        cwd: File = File(System.getProperty("user.dir")).absoluteFile,
        env: Map<String, String> = System.getenv(),
        runner: (File, ClientRequest) -> Int = { root, req -> run(root, req) },
    ): Int {
        when (args.firstOrNull()) {
            "--version", "-V" -> {
                println("${KtricsVersion.NAME} ${KtricsVersion.VERSION} (protocol v${Protocol.VERSION})")
                return ExitCode.OK.code
            }
        }
        val request =
            ClientRequest(
                argv = args,
                cwd = cwd.absolutePath,
                env = relayEnv(env),
            )
        return runner(resolveProjectRoot(args, cwd), request)
    }

    /** Wires the real launcher + client, then runs the relay orchestration. */
    fun run(
        projectRoot: File,
        request: ClientRequest,
        launcher: DaemonLauncher = DaemonLauncher(projectRoot),
        client: DaemonClient = DaemonClient(DaemonEndpoint.socketPath(projectRoot)),
    ): Int =
        relay(
            ensureRunning = launcher::ensureRunning,
            respawn = launcher::respawn,
            relayOnce = { client.relay(request) },
        )

    /**
     * Ensures the daemon is up, relays once, and retries exactly once after a version-mismatch respawn.
     * Pure orchestration over injected seams — no sockets or processes of its own.
     */
    fun relay(
        ensureRunning: () -> Boolean,
        respawn: () -> Boolean,
        relayOnce: () -> DaemonClient.Outcome,
    ): Int {
        if (!ensureRunning()) {
            System.err.println("ktrics: could not start ktricsd (daemon). See `ktrics daemon status`.")
            return ExitCode.INTERNAL.code
        }
        return when (val outcome = relayOnce()) {
            is DaemonClient.Outcome.Exited -> outcome.code
            is DaemonClient.Outcome.VersionMismatch -> {
                System.err.println("ktrics: daemon protocol v${outcome.daemonVersion} != v${Protocol.VERSION}; restarting.")
                if (!respawn()) return ExitCode.INTERNAL.code
                when (val retry = relayOnce()) {
                    is DaemonClient.Outcome.Exited -> retry.code
                    else -> ExitCode.INTERNAL.code
                }
            }
            DaemonClient.Outcome.Unreachable -> {
                System.err.println("ktrics: daemon unreachable after spawn.")
                ExitCode.INTERNAL.code
            }
        }
    }

    /** Project root = nearest ancestor with `ktrics.yaml`, an explicit `--root`, else [cwd]. */
    fun resolveProjectRoot(
        args: List<String>,
        cwd: File,
    ): File {
        val explicit = args.zipWithNext().firstOrNull { it.first == "--root" }?.second
        if (explicit != null) return File(explicit).absoluteFile.normalize()
        var dir: File? = cwd.absoluteFile
        while (dir != null) {
            if (File(dir, "ktrics.yaml").exists()) return dir
            dir = dir.parentFile
        }
        return cwd.absoluteFile.normalize()
    }

    /** Curated env relayed to the daemon: enough for git shell-out and locale, nothing more. */
    fun relayEnv(env: Map<String, String>): Map<String, String> {
        val keep = setOf("PATH", "HOME", "JAVA_HOME", "LANG", "LC_ALL", "GIT_DIR", "GIT_WORK_TREE")
        return buildMap {
            env.forEach { (k, v) -> if (k in keep || k.startsWith("KTRICS_")) put(k, v) }
        }
    }
}
