plugins {
    application
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ktricsd: the warm JVM. Embeds the K2/IntelliJ platform via :engine, holds the
// module-aware cross-file index + snapshot in memory, serves the socket. Shipped via `installDist` as
// an image of launcher scripts (bin/) + app jars (lib/) with NO bundled JRE — it runs on the caller's
// system Java 21+ (the daemon bytecode is Java 21). The client preflights that runtime and surfaces a
// clear error when it is missing or older, so the dependency is explicit rather than a silent failure.
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

// `installDist` emits build/install/ktricsd/ with bin/ launcher scripts + lib/*.jar and no bundled JRE.
// The generated start script resolves `java` from JAVA_HOME (else PATH) at run time, so the shipped
// archive needs a system JDK 21+ — DaemonLauncher preflights exactly that before spawning. The embedded
// IntelliJ platform + Kotlin compiler are non-modular; a standard full JDK carries every module they
// reach for (java.se, jdk.compiler/unsupported/zipfs/attach/jdi/crypto.ec), validated by the native CI
// job's smoke test on each target OS.
