plugins {
    application
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.graalvm.native)
}

// The thin native client. Links NONE of the platform: reads argv + cwd, ensures the daemon is
// up (auto-spawn), relays over the socket, streams stdout/stderr, returns the exit code. Compiles
// to a GraalVM native-image (ms start, single binary, zero JDK dependency on the caller). It also
// OWNS the wire protocol DTOs (platform-free) shared with the daemon.
//
// CI asserts this module links none of the platform via a reflection-config + size check.
dependencies {
    implementation(libs.clikt)
    implementation(libs.serialization.json)
}

application {
    applicationName = "ktrics"
    mainClass.set("dev.ktrics.client.MainKt")
}

// KtricsVersion is a compile-time const (native-image bakes it in; no manifest exists at run time),
// so VersionSyncTest pins it against the build's resolved `ktrics.version` — drift fails CI.
tasks.withType<Test>().configureEach {
    systemProperty("ktrics.build.version", version.toString())
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("ktrics")
            mainClass.set("dev.ktrics.client.MainKt")
            // Millisecond start: no platform reflection to register. The build fails if any platform
            // type sneaks onto the classpath (it would demand reflect-config we deliberately omit).
            buildArgs.add("--no-fallback")
            buildArgs.add("-O3")
            buildArgs.add("--initialize-at-build-time=kotlin,kotlinx.serialization")
        }
    }
    // Toolchain detection off: pinned GraalVM provided by CI.
    toolchainDetection.set(false)
}
