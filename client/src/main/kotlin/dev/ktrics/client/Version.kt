package dev.ktrics.client

/**
 * Client build identity. The native-image build bakes this in; the daemon advertises its own via the
 * jar manifest. A client/daemon version handshake compares them and restarts the daemon on mismatch
 * so the loop never serves stale logic.
 */
object KtricsVersion {
    const val VERSION: String = "0.1.0-SNAPSHOT"
    const val NAME: String = "ktrics"
}
