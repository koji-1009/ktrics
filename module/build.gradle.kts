plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The declarative module-graph model: declared modules, dependency edges, classpath resolution.
// Deliberately platform-free and fully unit-testable — the KaModule *translation* lives in
// :frontend, the only place permitted to touch the platform. Platform-touching does not leak
// outside the daemon's frontend. Sourcing the graph is staged: v1 reads it from ktrics.yaml /
// --module (here); v2 derives it via the Gradle/Maven build tools.
dependencies {
    implementation(libs.serialization.json)
}
