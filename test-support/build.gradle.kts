// Platform-free shared test fixtures. Consumed by other modules via
// `testImplementation(project(":test-support"))`; the fixtures live in `main` so they are visible on
// consumers' test classpaths. Deliberately links NONE of the Analysis API — pure-logic tests stay
// fast and session-free. The heavy real-session harness lives in :test-session instead.
dependencies {
    api(project(":ir"))
    api(project(":lang-api"))
    // com.intellij.psi.PsiElement for the stub nodes; the same monolithic compiler the IR uses.
    api(libs.kotlin.compiler)
}
