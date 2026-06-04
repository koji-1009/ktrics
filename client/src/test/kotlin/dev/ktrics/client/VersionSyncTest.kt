package dev.ktrics.client

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [KtricsVersion.VERSION] is a compile-time const — the native-image client cannot read a manifest
 * at run time — so nothing structural forces it to match the build's `ktrics.version` property.
 * This guard does: the build passes the resolved version into the test JVM, and drift fails CI.
 */
class VersionSyncTest {
    @Test
    fun `the baked-in client version matches the build's ktrics-version property`() {
        KtricsVersion.VERSION shouldBe System.getProperty("ktrics.build.version")
    }
}
