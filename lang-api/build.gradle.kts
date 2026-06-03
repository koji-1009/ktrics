// NodeClassifier (over com.intellij.psi.PsiElement) + IR-lowering interfaces.
// Metric calculators are written once against this; per-language dispatch lives in the frontend impls.
dependencies {
    api(project(":ir"))
    api(libs.kotlin.compiler)
    testImplementation(project(":test-support"))
}
