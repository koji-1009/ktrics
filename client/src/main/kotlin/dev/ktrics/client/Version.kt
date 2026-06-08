package dev.ktrics.client

/**
 * Client build identity — the human-facing version (`ktrics --version`); the daemon reads its own
 * from the jar manifest, and the socket handshake guards the PROTOCOL version separately.
 *
 * This must be a compile-time const (the native-image client has no manifest to read at run time),
 * so nothing structural ties it to `gradle.properties ktrics.version` — VersionSyncTest does:
 * the build passes the resolved version into the test JVM and drift fails CI.
 */
object KtricsVersion {
    const val VERSION: String = "0.0.2"
    const val NAME: String = "ktrics"
}
