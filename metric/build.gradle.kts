plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ALL calculators, written ONCE against ir + NodeClassifier.
// Also home to the result/violation model and the builtin metric catalog (catalog.kt).
dependencies {
    api(project(":ir"))
    api(project(":lang-api"))
    implementation(libs.serialization.json)
    testImplementation(project(":test-support"))
    testImplementation(project(":test-session"))
}
