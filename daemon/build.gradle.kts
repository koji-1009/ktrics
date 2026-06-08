plugins {
    application
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.shadow)
    alias(libs.plugins.badass.runtime)
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

// --- Self-contained image: jlink a trimmed JRE alongside the daemon jars (org.beryx.runtime). ---
// The shipped archive must need NEITHER a system JDK NOR Gradle (the README's stated design). The
// `runtime` task emits build/image/ with bin/ launchers, lib/*.jar, and a bundled `runtime/` JRE whose
// launcher points at it. jlink can't cross-compile, so each target OS builds its own image in the
// release workflow — the same constraint the GraalVM native client already lives under.
runtime {
    // JDK 21 jlink: `--compress=zip-N` (the legacy `--compress 2` is deprecated). Strip what a runtime
    // never needs; keep it correct over maximally small.
    options.set(listOf("--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-6"))
    // The embedded IntelliJ platform + Kotlin compiler are NON-modular jars, so jdeps can't reliably
    // derive the module set. Include the full Java SE aggregate plus the jdk.* modules the platform
    // actually reaches for (sun.misc.Unsafe via jdk.unsupported, the zip filesystem provider, the
    // compiler/tools + attach/debug surface). Erring broad keeps the daemon correct at a modest size
    // cost; the native-build CI job's smoke test (ktricsd --version + a real analyze) validates the set
    // on every target OS before a release is cut.
    modules.set(
        listOf(
            "java.se",
            "jdk.unsupported",
            "jdk.zipfs",
            "jdk.compiler",
            "jdk.management",
            "jdk.attach",
            "jdk.jdi",
            "jdk.crypto.ec",
        ),
    )
}
