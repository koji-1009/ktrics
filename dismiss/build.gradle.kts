plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Dismissal channels: comment form `// ktrics:dismiss <metric> reason="..."` directly above the
// declaration (blank line invalidates) + YAML sidecar `ktrics-dismissals.yaml` (wins on collision).
// Reasons under minReasonLength keep the violation live with `dismissalRejected`.
dependencies {
    api(project(":ir"))
    api(project(":metric"))
    implementation(libs.kaml)
    implementation(libs.serialization.json)
}
