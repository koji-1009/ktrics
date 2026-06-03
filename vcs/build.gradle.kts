plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

// git shell-out: --since, regression, snapshot. Identical semantics to the siblings;
// the daemon keeps these warm. Unresolved ref -> exit 65.
dependencies {
    api(project(":ir"))
    api(project(":metric"))
    implementation(libs.serialization.json)
}
