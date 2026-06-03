package dev.ktrics.langapi

import com.intellij.psi.PsiElement
import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit

/**
 * Lowers one parsed file (a Kotlin `KtFile` or a Java `PsiJavaFile`, both [PsiElement]) into the
 * normalized [SourceUnit] IR, and exposes the [NodeClassifier] for that language.
 *
 * The ONLY per-language code in the system: two impls (`KotlinFrontend`, `JavaFrontend`). The metric
 * engine consumes [SourceUnit] + [classifier] and never knows which language produced them.
 */
interface LanguageFrontend {
    val lang: Lang
    val classifier: NodeClassifier

    /** True if this frontend can lower [file] (i.e. it is this language's file type). */
    fun accepts(file: PsiElement): Boolean

    /** Lower a parsed file to IR. Caller guarantees [accepts] returned true. */
    fun lower(file: PsiElement): SourceUnit
}

/** Resolves the right [LanguageFrontend] for a parsed file. */
class FrontendRegistry(private val frontends: List<LanguageFrontend>) {
    fun forFile(file: PsiElement): LanguageFrontend? = frontends.firstOrNull { it.accepts(file) }

    fun forLang(lang: Lang): LanguageFrontend? = frontends.firstOrNull { it.lang == lang }

    fun all(): List<LanguageFrontend> = frontends
}
