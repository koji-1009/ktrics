plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Normalized, language-agnostic model. A Scope carries structured metadata PLUS a
// handle to its frontend node (a com.intellij.psi.PsiElement) for token-level and walk-based
// metrics — hence the intellij-psi dependency. This is daemon-side; the client never links it.
dependencies {
    // com.intellij.psi.PsiElement (the node handle) from the monolithic compiler — the single,
    // unrelocated source shared with the frontends so "one PsiElement, both languages" holds.
    api(libs.kotlin.compiler)
    implementation(libs.serialization.json)
    testImplementation(project(":test-support"))
}
