plugins {
    application
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.shadow)
}

// ktricsd: the warm JVM. Embeds the K2/IntelliJ platform via :engine, holds the
// module-aware cross-file index + snapshot in memory, serves the socket. Shipped as a jlink/jpackage
// self-contained image (trimmed JRE bundled) so the caller needs neither a JDK nor Gradle.
//
// Depends on :client only for the shared wire-protocol DTOs (platform-free) — never the reverse.
dependencies {
    implementation(project(":engine"))
    implementation(project(":client"))
    implementation(libs.clikt)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}

application {
    applicationName = "ktricsd"
    mainClass.set("dev.ktrics.daemon.MainKt")
    // The platform wants a generous heap + the module-system opens it needs for reflection.
    applicationDefaultJvmArgs =
        listOf(
            "-Xmx4g",
            "-XX:+UseParallelGC",
            // AppCDS share archive cuts warmup — OPT-IN (it warns on a cold first run with no
            // base archive). Enable for the warm daemon via:
            //   KTRICSD_OPTS="-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=ktricsd.jsa"
        )
}

// Implementation-Version is stamped by the root convention for every module jar; this block only
// adds the daemon-specific attributes on top.
tasks.named<Jar>("jar") {
    manifest {
        attributes["Implementation-Title"] = "ktricsd"
        // The client/daemon version handshake uses this: mismatch triggers daemon restart.
        attributes["Ktrics-Protocol-Version"] = "1"
    }
}
