package dev.ktrics.frontend.kotlin

import com.intellij.psi.PsiElement
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SymbolRef
import dev.ktrics.ir.TypeRef
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.util.WeakHashMap

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
    /**
     * Per-`when` memo of "the subject resolves to a sealed class" — decisionWeight is called for
     * every entry of a when during one cyclomatic walk, and the subject's type never changes within
     * a warm session. WeakHashMap so a disposed session's PSI does not pin entries.
     */
    private val sealedSubjectCache = WeakHashMap<KtWhenExpression, Boolean>()

    /**
     * Cyclomatic discount for exhaustive sealed dispatch (ports the sibling tool's sealed-switch
     * rule): the compiler enforces exhaustiveness on a `when` over a sealed subject, so the arms are
     * an enumeration, not a "did I forget one?" decision load. Counts normally when the subject
     * cannot be resolved (name-based runs) — the safe, documented degrade.
     */
    override fun decisionWeight(n: PsiElement): Int {
        if (n is KtWhenEntry && !n.isElse && isSealedSubject(n.parent as? KtWhenExpression)) return 0
        return super.decisionWeight(n)
    }

    private fun isSealedSubject(whenExpr: KtWhenExpression?): Boolean {
        if (whenExpr == null) return false
        return sealedSubjectCache.getOrPut(whenExpr) {
            val subject = whenExpr.subjectExpression ?: return@getOrPut false
            runCatching {
                analyze(subject) {
                    subject.expressionType?.expandedSymbol?.modality == KaSymbolModality.SEALED
                }
            }.getOrDefault(false)
        }
    }

    override fun referencedTypes(scope: PsiElement): List<TypeRef> {
        val element = scope as? KtElement ?: return super.referencedTypes(scope)
        return runCatching {
            analyze(element) {
                element.collectDescendantsOfType<KtTypeReference>()
                    .mapNotNull { typeRefOf(it) }
                    .distinctBy { it.key }
            }
        }.getOrElse { super.referencedTypes(scope) }
    }

    /**
     * One type reference → a coupling ref, or null when it is no class coupling. A generic type
     * PARAMETER (e.g. `T`) resolves to a KaTypeParameterType (no classId) and would otherwise leak in
     * as a spurious NAME_BASED ref to `T`, dragging the class's CBO stamp to name-based — skip it,
     * exactly as builtin primitives (kotlin.Int/…) are skipped (CBO; CK 1994). A resolvable class
     * becomes a RESOLVED qualified ref; anything else degrades to the written text, name-based.
     */
    private fun KaSession.typeRefOf(ref: KtTypeReference): TypeRef? {
        val type = ref.type
        if (type is KaTypeParameterType) return null
        val classId = type.expandedSymbol?.classId
        if (classId != null) {
            val fqn = classId.asFqNameString()
            if (isKotlinPrimitiveType(fqn)) return null
            return TypeRef(
                name = classId.shortClassName.asString(),
                qualifiedName = fqn,
                packageName = classId.packageFqName.asString(),
                resolution = Resolution.RESOLVED,
            )
        }
        val text = ref.typeElement?.text?.let { simpleTypeName(it) }
        return if (text.isNullOrBlank() || isKotlinPrimitiveType(text)) null else TypeRef(text, null, null, Resolution.NAME_BASED)
    }

    override fun calledSymbols(scope: PsiElement): List<SymbolRef> {
        val element = scope as? KtElement ?: return super.calledSymbols(scope)
        return runCatching {
            analyze(element) {
                val calls =
                    element.collectDescendantsOfType<KtCallExpression>().mapNotNull { call ->
                        val symbol = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol
                        val callableId = symbol?.callableId
                        if (callableId != null) {
                            SymbolRef(callableId.callableName.asString(), callableId.classId?.asFqNameString(), Resolution.RESOLVED)
                        } else {
                            super.calledSymbols(call).firstOrNull()
                        }
                    }
                // Operator forms (`a[i]`, `a + b`, `-a`, `a in b`, …) resolve to the convention
                // function they invoke (the dartrics 0.7.3 analogue). A form that does not resolve is
                // SKIPPED here, not degraded: the inherited referencedNames channel already carries
                // its convention name, so reachability is safe without poisoning the resolution stamp.
                // Stdlib targets are equally skipped — see [resolvedProjectOperatorId].
                val operators =
                    collectOperatorNodes(element).mapNotNull { op ->
                        resolvedProjectOperatorId(op)?.let { id ->
                            SymbolRef(id.callableName.asString(), id.classId?.asFqNameString(), Resolution.RESOLVED)
                        }
                    }
                (calls + operators).distinctBy { it.key }
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
                // Operator forms become exact resolved keys (so `a + b` attributes fan-in to the exact
                // `plus` it targets); an UNRESOLVED form degrades to its bare convention name, the
                // same shape the name-based classifier emits — while a stdlib-resolved form is dropped
                // entirely (degrading it would over-connect every project `plus` from Int arithmetic).
                val operators =
                    collectOperatorNodes(element).mapNotNull { op ->
                        val id = resolvedOperatorId(op) ?: return@mapNotNull conventionNameOf(op)
                        id.takeUnless { isStdlibPackage(it.packageName.asString()) }?.let(::callableKey)
                    }
                val types =
                    element.collectDescendantsOfType<KtTypeReference>()
                        .mapNotNull { ref ->
                            ref.type.expandedSymbol?.classId?.asFqNameString() ?: ref.typeElement?.text?.let { simpleTypeName(it) }
                        }
                        .filterNot { isKotlinPrimitiveType(it) }
                (calls + operators + types).filterNotNull()
            }
        }.getOrElse { super.outgoingRefNames(scope) }
    }

    /**
     * The PSI shapes that desugar to convention-function calls and support `resolveToCall`, collected
     * in ONE subtree pass (this runs per declaration for both the unused sweep and the call graph).
     * Deliberately NARROWER than the name-based channel: for-loops, destructuring, and delegates have
     * no direct `resolveToCall` in the pinned API, so they feed reachability through the inherited
     * [referencedNames] convention names instead of exact call-graph keys.
     */
    private fun collectOperatorNodes(element: KtElement): List<KtExpression> =
        buildList {
            for (n in sequenceOf<PsiElement>(element) + descendants(element)) {
                when {
                    n is KtBinaryExpression && n.operationToken in BINARY_OPERATOR_CONVENTIONS -> add(n)
                    n is KtUnaryExpression && conventionNameOf(n) != null -> add(n)
                    n is KtArrayAccessExpression -> add(n)
                }
            }
        }

    /** The CallableId an operator form resolves to, or null when the call does not resolve. */
    private fun KaSession.resolvedOperatorId(op: KtExpression): CallableId? =
        op.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId

    /**
     * [resolvedOperatorId] narrowed to non-stdlib targets: like primitives in CBO, built-in
     * arithmetic (`Int.times`, `String.plus`) is not a "message" — only user-defined operators
     * count (RFC), and a stdlib edge could never match a project declaration anyway.
     */
    private fun KaSession.resolvedProjectOperatorId(op: KtExpression): CallableId? =
        resolvedOperatorId(op)?.takeUnless { isStdlibPackage(it.packageName.asString()) }

    /** `kotlin` and `kotlin.*` (NOT `kotlinx.*`): stdlib operator targets are built-ins, not messages. */
    private fun isStdlibPackage(pkg: String): Boolean = pkg == "kotlin" || pkg.startsWith("kotlin.")

    /** Bare convention-name fallback for an operator node whose call did not resolve. */
    private fun conventionNameOf(op: KtExpression): String? =
        when (op) {
            is KtArrayAccessExpression -> "get"
            is KtBinaryExpression -> BINARY_OPERATOR_CONVENTIONS[op.operationToken]?.first()
            is KtPrefixExpression -> PREFIX_OPERATOR_CONVENTIONS[op.operationReference.getReferencedNameElementType()]
            is KtPostfixExpression -> POSTFIX_OPERATOR_CONVENTIONS[op.operationReference.getReferencedNameElementType()]
            else -> null
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
