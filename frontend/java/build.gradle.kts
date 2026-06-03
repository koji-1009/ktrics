// Java PSI (PsiJavaFile) -> IR including method bodies; the PsiElement classifier's Java dispatch.
// Body-level metrics for Java come from Java PSI, NOT from FIR.
// IntelliJ Java PSI comes transitively via :frontend (declared there as `api`).
dependencies {
    api(project(":frontend"))
    implementation(project(":ir"))
    implementation(project(":lang-api"))
    testImplementation(project(":test-session"))
}
