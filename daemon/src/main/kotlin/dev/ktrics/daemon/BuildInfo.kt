package dev.ktrics.daemon

/** Daemon build identity, read from the jar manifest with a constant fallback for dev runs. */
object BuildInfo {
    val VERSION: String =
        BuildInfo::class.java.`package`?.implementationVersion ?: "0.1.0-SNAPSHOT"
}
