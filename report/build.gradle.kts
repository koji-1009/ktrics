plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

// console | json | md | ai | sarif. The `ai` reporter is the primary integration
// surface; header `# ktrics ai-report v1` is a contractual constant.
dependencies {
    api(project(":ir"))
    api(project(":metric"))
    implementation(libs.serialization.json)
}
