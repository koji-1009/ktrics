// Kotlin PSI (KtFile) + Analysis API -> IR; the PsiElement classifier's Kotlin dispatch.
// The Analysis API + IntelliJ PSI come transitively via :frontend (declared there as `api`).
dependencies {
    api(project(":frontend"))
    implementation(project(":ir"))
    implementation(project(":lang-api"))
    testImplementation(project(":test-session"))
    // The operator-convention end-to-end test runs the unused sweep over this module's classifier.
    testImplementation(project(":unused"))
}
