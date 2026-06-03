plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ktrics.yaml loader + schema validation (doctor). YAML via kaml; doctor validates
// against the builtin catalog (hence the :metric dependency).
dependencies {
    api(project(":ir"))
    api(project(":module"))
    implementation(project(":metric"))
    implementation(libs.kaml)
    implementation(libs.serialization.json)
    testImplementation(project(":test-support"))
}
