package dev.ktrics.frontend

import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

/** Walks up from the working directory to the repo root (the dir holding settings.gradle.kts). */
internal fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").exists()) return dir
        dir = dir.parentFile
    }
    error("Could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
}

/**
 * Resolves every Java method call in this file to `Owner.method` strings via the shared symbol
 * space. A call into a Kotlin declaration resolves to the Kotlin symbol — that is the Java→Kotlin
 * direction the spike asserts.
 */
internal fun PsiJavaFile.collectMethodCallTargets(): List<String> =
    PsiTreeUtil.collectElementsOfType(this, PsiMethodCallExpression::class.java).mapNotNull { call ->
        val method = call.resolveMethod() ?: return@mapNotNull null
        val owner = method.containingClass?.qualifiedName ?: return@mapNotNull null
        "$owner.${method.name}"
    }
