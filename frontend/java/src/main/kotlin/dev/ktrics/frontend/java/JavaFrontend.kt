package dev.ktrics.frontend.java

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import dev.ktrics.frontend.SpanFactory
import dev.ktrics.frontend.projectRelativePath
import dev.ktrics.ir.FieldDecl
import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl
import dev.ktrics.ir.TypeKind
import dev.ktrics.langapi.LanguageFrontend
import dev.ktrics.langapi.NodeClassifier
import java.io.File

/**
 * Lowers a Java `PsiJavaFile` to the normalized [SourceUnit] IR. Java method
 * bodies come from Java PSI (NOT FIR) — the make-or-break surface the spike proved.
 * A Java file is structurally ~one public type, so it has no top-level functions/properties.
 */
class JavaFrontend(
    private val projectRoot: File,
    /** When true, use the resolution-backed classifier (Java PSI `.resolve()`). */
    resolved: Boolean = false,
) : LanguageFrontend {
    override val lang: Lang = Lang.JAVA
    override val classifier: NodeClassifier = if (resolved) ResolvedJavaClassifier() else JavaClassifier()

    override fun accepts(file: PsiElement): Boolean = file is PsiJavaFile

    override fun lower(file: PsiElement): SourceUnit {
        val java = file as PsiJavaFile
        val path = relativePath(java)
        val spans = SpanFactory(java, path)
        val imports = java.importList?.importStatements?.mapNotNull { it.qualifiedName } ?: emptyList()
        val types = java.classes.map { lowerType(it, spans) }
        return SourceUnit(
            path = path,
            lang = Lang.JAVA,
            packageName = java.packageName,
            imports = imports,
            types = types,
            // Java has no top-level members
            topLevelFns = emptyList(),
            topLevelProps = emptyList(),
            span = spans.wholeFile(),
            node = java,
        )
    }

    private fun lowerType(
        psi: PsiClass,
        spans: SpanFactory,
    ): TypeDecl {
        val kind = kindOf(psi)
        val modifiers = classifier.modifiers(psi)
        return TypeDecl(
            kind = kind,
            name = psi.name ?: "<anonymous>",
            qualifiedName = psi.qualifiedName,
            isAbstract = modifiers.isAbstract || kind.isInherentlyAbstract,
            supertypes = classifier.supertypes(psi),
            fields = psi.fields.map { lowerField(it, spans) },
            methods = psi.methods.map { lowerMethod(it, spans) },
            nested = psi.innerClasses.map { lowerType(it, spans) },
            modifiers = modifiers,
            annotations = classifier.annotations(psi),
            span = spans.of(psi),
            node = psi,
            lang = Lang.JAVA,
        )
    }

    private fun kindOf(psi: PsiClass): TypeKind =
        when {
            psi.isAnnotationType -> TypeKind.ANNOTATION
            psi.isInterface -> TypeKind.INTERFACE
            psi.isEnum -> TypeKind.ENUM
            psi.isRecord -> TypeKind.RECORD
            else -> TypeKind.CLASS
        }

    private fun lowerMethod(
        psi: PsiMethod,
        spans: SpanFactory,
    ): FunctionDecl =
        FunctionDecl(
            name = psi.name,
            params = classifier.parameters(psi),
            modifiers = classifier.modifiers(psi),
            annotations = classifier.annotations(psi),
            span = spans.of(psi),
            node = psi,
            // null for abstract/interface methods — body-less, as expected
            bodyNode = psi.body,
            lang = Lang.JAVA,
            isConstructor = psi.isConstructor,
        )

    private fun lowerField(
        psi: PsiField,
        spans: SpanFactory,
    ): FieldDecl =
        FieldDecl(
            name = psi.name,
            typeName = psi.type.presentableText,
            modifiers = classifier.modifiers(psi),
            annotations = classifier.annotations(psi),
            span = spans.of(psi),
            node = psi,
            isProperty = false,
        )

    private fun relativePath(file: PsiJavaFile): String = projectRelativePath(file.virtualFile?.path ?: file.name, projectRoot)
}
