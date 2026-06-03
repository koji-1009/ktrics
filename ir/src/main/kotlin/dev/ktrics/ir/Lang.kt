package dev.ktrics.ir

/**
 * The two languages read through the single K2/JetBrains host.
 *
 * Both are lowered to one IR over [com.intellij.psi.PsiElement]; language dispatch lives only in the
 * `NodeClassifier` impls, never in metric calculators.
 */
enum class Lang(val id: String, val displayName: String) {
    JAVA("java", "Java"),
    KOTLIN("kotlin", "Kotlin"),
    ;

    companion object {
        fun fromExtension(ext: String): Lang? =
            when (ext.lowercase()) {
                "java" -> JAVA
                "kt", "kts" -> KOTLIN
                else -> null
            }

        fun fromId(id: String): Lang? = entries.firstOrNull { it.id == id.lowercase() }
    }
}
