package dev.ktrics.frontend.kotlin

import com.intellij.psi.PsiElement
import dev.ktrics.frontend.SpanFactory
import dev.ktrics.ir.FieldDecl
import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.Lang
import dev.ktrics.ir.Modifiers
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl
import dev.ktrics.ir.TypeKind
import dev.ktrics.langapi.LanguageFrontend
import dev.ktrics.langapi.NodeClassifier
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import java.io.File

/**
 * Lowers a Kotlin `KtFile` to the normalized [SourceUnit] IR. Kotlin may hold many
 * top-level types plus loose functions/properties — these top-level declarations belong to no class
 * and are carried separately so the file-level lenses can see them; they are NOT folded
 * into a synthetic class.
 */
class KotlinFrontend(
    private val projectRoot: File,
    /** When true, use the resolution-backed classifier (`analyze {}`). */
    resolved: Boolean = false,
) : LanguageFrontend {
    override val lang: Lang = Lang.KOTLIN
    override val classifier: NodeClassifier = if (resolved) ResolvedKotlinClassifier() else KotlinClassifier()

    override fun accepts(file: PsiElement): Boolean = file is KtFile

    override fun lower(file: PsiElement): SourceUnit {
        val kt = file as KtFile
        val path = relativePath(kt)
        val spans = SpanFactory(kt, path)
        val pkg = kt.packageFqName.asString()
        val imports = kt.importDirectives.mapNotNull { it.importedFqName?.asString() }

        val topTypes = kt.declarations.filterIsInstance<KtClassOrObject>().map { lowerType(it, spans) }
        val topFns = kt.declarations.filterIsInstance<KtNamedFunction>().map { lowerFunction(it, spans) }
        val topProps = kt.declarations.filterIsInstance<KtProperty>().map { lowerProperty(it, spans) }

        return SourceUnit(
            path = path,
            lang = Lang.KOTLIN,
            packageName = pkg,
            imports = imports,
            types = topTypes,
            topLevelFns = topFns,
            topLevelProps = topProps,
            span = spans.wholeFile(),
            node = kt,
        )
    }

    private fun lowerType(
        decl: KtClassOrObject,
        spans: SpanFactory,
    ): TypeDecl {
        val kind = kindOf(decl)
        val bodyDecls = decl.declarations
        val fields =
            buildList {
                // Primary-constructor `val`/`var` parameters are properties.
                decl.primaryConstructorParameters.filter { it.hasValOrVar() }.forEach { p ->
                    add(
                        FieldDecl(
                            name = p.name ?: "_",
                            typeName = p.typeReference?.text ?: "Any",
                            modifiers = classifier.modifiers(p),
                            annotations = classifier.annotations(p),
                            span = spans.of(p),
                            node = p,
                            isProperty = true,
                        ),
                    )
                }
                bodyDecls.filterIsInstance<KtProperty>().forEach { add(lowerProperty(it, spans)) }
            }
        val methods =
            buildList {
                bodyDecls.filterIsInstance<KtNamedFunction>().forEach { add(lowerFunction(it, spans)) }
                bodyDecls.filterIsInstance<KtSecondaryConstructor>().forEach { add(lowerConstructor(it, spans)) }
            }
        // KtEnumEntry extends KtClass, so a plain filterIsInstance would turn each enum constant into
        // a spurious nested CLASS — exclude them; they are values, not types.
        val nested =
            bodyDecls.filterIsInstance<KtClassOrObject>()
                .filterNot { it is KtEnumEntry }
                .map { lowerType(it, spans) }
        val modifiers = classifier.modifiers(decl)
        return TypeDecl(
            kind = kind,
            name = decl.name ?: "<anonymous>",
            qualifiedName = decl.fqName?.asString(),
            isAbstract = modifiers.isAbstract || kind.isInherentlyAbstract,
            supertypes = classifier.supertypes(decl),
            fields = fields,
            methods = methods,
            nested = nested,
            modifiers = modifiers,
            annotations = classifier.annotations(decl),
            span = spans.of(decl),
            node = decl,
            lang = Lang.KOTLIN,
        )
    }

    private fun kindOf(decl: KtClassOrObject): TypeKind =
        when {
            decl is KtObjectDeclaration -> TypeKind.OBJECT
            decl !is KtClass -> TypeKind.CLASS // not an object and not a class declaration: plain CLASS
            decl.isInterface() -> TypeKind.INTERFACE
            decl.isEnum() -> TypeKind.ENUM
            decl.isAnnotation() -> TypeKind.ANNOTATION
            decl.isSealed() -> TypeKind.SEALED
            else -> TypeKind.CLASS
        }

    private fun lowerFunction(
        fn: KtNamedFunction,
        spans: SpanFactory,
    ): FunctionDecl =
        FunctionDecl(
            name = fn.name ?: "<anonymous>",
            params = classifier.parameters(fn),
            modifiers = classifier.modifiers(fn),
            annotations = classifier.annotations(fn),
            span = spans.of(fn),
            node = fn,
            bodyNode = fn.bodyBlockExpression ?: fn.bodyExpression,
            lang = Lang.KOTLIN,
        )

    private fun lowerConstructor(
        ctor: KtConstructor<*>,
        spans: SpanFactory,
    ): FunctionDecl =
        FunctionDecl(
            name = "<init>",
            params = classifier.parameters(ctor),
            modifiers = classifier.modifiers(ctor),
            annotations = classifier.annotations(ctor),
            span = spans.of(ctor),
            node = ctor,
            bodyNode = (ctor as? KtSecondaryConstructor)?.bodyExpression,
            lang = Lang.KOTLIN,
            isConstructor = true,
        )

    private fun lowerProperty(
        prop: KtProperty,
        spans: SpanFactory,
    ): FieldDecl =
        FieldDecl(
            name = prop.name ?: "_",
            typeName = prop.typeReference?.text ?: "Any",
            modifiers = runCatching { classifier.modifiers(prop) }.getOrDefault(Modifiers.PUBLIC),
            annotations = classifier.annotations(prop),
            span = spans.of(prop),
            node = prop,
            isProperty = true,
        )

    private fun relativePath(file: KtFile): String {
        val abs = file.virtualFile?.path ?: file.name
        val rootPath = projectRoot.absolutePath
        return if (abs.startsWith(rootPath)) abs.removePrefix(rootPath).trimStart(File.separatorChar) else abs
    }
}
