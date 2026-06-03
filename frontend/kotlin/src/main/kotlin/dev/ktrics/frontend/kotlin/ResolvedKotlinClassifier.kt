package dev.ktrics.frontend.kotlin

import com.intellij.psi.PsiElement
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SymbolRef
import dev.ktrics.ir.TypeRef
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Kotlin classifier with resolution turned ON. Overrides coupling/inheritance edges to
 * resolve inside `analyze {}` over the shared symbol space — so a Kotlin→Java edge resolves to the
 * Java symbol's qualified name, cross-language, with NO calculator change.
 *
 * Each resolution is guarded: any failure (an edge into a dependency missing from the classpath, or
 * an API shape this pinned version doesn't expose) degrades that single edge to name-based — exactly
 * the documented behavior. All `analyze {}` work stays inside the daemon.
 */
class ResolvedKotlinClassifier : KotlinClassifier() {
    override fun referencedTypes(scope: PsiElement): List<TypeRef> {
        val element = scope as? KtElement ?: return super.referencedTypes(scope)
        return runCatching {
            analyze(element) {
                element.collectDescendantsOfType<KtTypeReference>().mapNotNull { ref ->
                    val type = ref.type
                    // A generic type PARAMETER (e.g. `T`) is not a class coupling — it has no classId and would
                    // otherwise leak in as a spurious NAME_BASED ref to `T`, dragging the class's CBO stamp to
                    // name-based. A type-parameter reference resolves to a KaTypeParameterType (whose symbol is a
                    // KaTypeParameterSymbol); skip it, exactly as builtin primitives are skipped.
                    if (type is KaTypeParameterType) {
                        return@mapNotNull null
                    }
                    val symbol = type.expandedSymbol
                    val classId = symbol?.classId
                    if (classId != null) {
                        val fqn = classId.asFqNameString()
                        // A builtin primitive (kotlin.Int/…) is not a class coupling — skip it (CBO; CK 1994).
                        if (isKotlinPrimitiveType(fqn)) {
                            null
                        } else {
                            TypeRef(
                                name = classId.shortClassName.asString(),
                                qualifiedName = fqn,
                                packageName = classId.packageFqName.asString(),
                                resolution = Resolution.RESOLVED,
                            )
                        }
                    } else {
                        val text = ref.typeElement?.text?.let { simpleTypeName(it) }
                        if (text.isNullOrBlank() || isKotlinPrimitiveType(text)) {
                            null
                        } else {
                            TypeRef(text, null, null, Resolution.NAME_BASED)
                        }
                    }
                }.distinctBy { it.key }
            }
        }.getOrElse { super.referencedTypes(scope) }
    }

    override fun calledSymbols(scope: PsiElement): List<SymbolRef> {
        val element = scope as? KtElement ?: return super.calledSymbols(scope)
        return runCatching {
            analyze(element) {
                element.collectDescendantsOfType<KtCallExpression>().mapNotNull { call ->
                    val symbol = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol
                    val callableId = symbol?.callableId
                    if (callableId != null) {
                        SymbolRef(callableId.callableName.asString(), callableId.classId?.asFqNameString(), Resolution.RESOLVED)
                    } else {
                        super.calledSymbols(call).firstOrNull()
                    }
                }.distinctBy { it.key }
            }
        }.getOrElse { super.calledSymbols(scope) }
    }

    /**
     * Resolution-aware call-graph edges: each call/type reference becomes its resolved fully-qualified
     * key (with multiplicity, NOT deduped), so the call graph matches it to the exact target instead of
     * every same-simple-named declaration. An edge that doesn't resolve degrades to its bare name.
     */
    override fun outgoingRefNames(scope: PsiElement): List<String> {
        val element = scope as? KtElement ?: return super.outgoingRefNames(scope)
        return runCatching {
            analyze(element) {
                val calls =
                    element.collectDescendantsOfType<KtCallExpression>().map { call ->
                        val callableId = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId
                        if (callableId != null) {
                            callableKey(callableId)
                        } else {
                            (call.calleeExpression as? KtSimpleNameExpression)?.getReferencedName()
                        }
                    }
                val types =
                    element.collectDescendantsOfType<KtTypeReference>()
                        .mapNotNull { ref ->
                            ref.type.expandedSymbol?.classId?.asFqNameString() ?: ref.typeElement?.text?.let { simpleTypeName(it) }
                        }
                        .filterNot { isKotlinPrimitiveType(it) }
                (calls + types).filterNotNull()
            }
        }.getOrElse { super.outgoingRefNames(scope) }
    }

    /** A callable's fully-qualified key: `Owner.method` for members, `pkg.fn` for top-level functions. */
    private fun callableKey(id: CallableId): String {
        id.classId?.let { return "${it.asFqNameString()}.${id.callableName.asString()}" }
        val pkg = id.packageName.asString()
        return if (pkg.isEmpty()) id.callableName.asString() else "$pkg.${id.callableName.asString()}"
    }

    override fun supertypes(type: PsiElement): List<TypeRef> {
        val decl = type as? KtClassOrObject ?: return super.supertypes(type)
        return runCatching {
            analyze(decl) {
                // Iterate the SYNTACTIC supertype entries (like the base classifier), not the resolved
                // `KaClassSymbol.superTypes` list. Resolving via symbol.superTypes drops any entry whose
                // expandedSymbol is null (an error/unresolved base) — a missing base class would vanish from
                // DIT/NOC. Per entry: emit a RESOLVED ref when the type resolves (excluding the implicit
                // kotlin.Any), else degrade to a NAME_BASED ref from the entry's written text — the same shape
                // the base KotlinClassifier produces (name = type text, qualifiedName = null, NAME_BASED).
                decl.superTypeListEntries.mapNotNull { entry ->
                    val classId = entry.typeReference?.type?.expandedSymbol?.classId
                    if (classId != null) {
                        classId.takeIf { it.asFqNameString() != "kotlin.Any" }
                            ?.let {
                                TypeRef(
                                    it.shortClassName.asString(),
                                    it.asFqNameString(),
                                    it.packageFqName.asString(),
                                    Resolution.RESOLVED,
                                )
                            }
                    } else {
                        entry.typeReference?.text?.let { TypeRef(it, null, null, Resolution.NAME_BASED) }
                    }
                }
            }
        }.getOrElse { super.supertypes(type) }
    }
}
