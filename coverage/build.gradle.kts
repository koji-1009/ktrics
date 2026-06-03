// JaCoCo XML primary (branch counters direct); lcov / Kover-XML secondary.
// Drives `complexityJustified`: branch coverage >= 0.8 from JaCoCo branch counters.
dependencies {
    api(project(":ir"))
    implementation(libs.serialization.json)
}
