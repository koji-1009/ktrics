package dev.ktrics.frontend.kotlin

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import dev.ktrics.ir.Modifiers
import dev.ktrics.ir.Param
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SymbolRef
import dev.ktrics.ir.Token
import dev.ktrics.ir.TokenKind
import dev.ktrics.ir.TypeRef
import dev.ktrics.ir.Visibility
import dev.ktrics.langapi.NodeClassifier
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Kotlin dispatch of the [NodeClassifier]. Aligns counting to the SonarSource Kotlin
 * analyzer: `when` counts like `switch`; `?:` (elvis) and `&&`/`||` feed cognitive B3; the nesting
 * penalty applies inside lambdas and scope functions; `?.` alone adds nothing. Deviations from the
 * cited papers are recorded in doc/calibration.md.
 *
 * It returns name-based references initially; these bodies are later swapped for resolved lookups inside
 * `analyze {}` with NO change to any calculator.
 */
open class KotlinClassifier : NodeClassifier {
    private val scopeFunctionNames = setOf("let", "run", "apply", "also", "with")

    override fun isDecisionPoint(n: PsiElement): Boolean =
        when (n) {
            is KtIfExpression -> true
            is KtForExpression, is KtWhileExpression, is KtDoWhileExpression -> true
            is KtCatchClause -> true
            is KtWhenExpression -> false // the entries carry the branches; counted below
            is KtBinaryExpression ->
                n.operationToken == KtTokens.ANDAND ||
                    n.operationToken == KtTokens.OROR ||
                    n.operationToken == KtTokens.ELVIS
            else -> isWhenEntryWithCondition(n)
        }

    override fun isNestingBoundary(n: PsiElement): Boolean =
        when (n) {
            is KtIfExpression, is KtForExpression, is KtWhileExpression, is KtDoWhileExpression,
            is KtWhenExpression, is KtCatchClause,
            -> true
            else -> false
        }

    // Lambdas and local functions raise the nesting level WITHOUT a B1 increment (SonarSource:
    // "nested methods and method-like structures"). This covers scope-function lambdas too — the
    // call expression itself is no longer a boundary, so a `let { }` nests exactly once.
    override fun isNestingOnlyBoundary(n: PsiElement): Boolean = n is KtLambdaExpression || (n is KtNamedFunction && n.isLocal)

    override fun isFlatIncrement(n: PsiElement): Boolean = isElseIfBranch(n) || isLabeledJump(n)

    override fun elseIncrement(n: PsiElement): Int {
        if (n !is KtIfExpression) return 0
        val elseBranch = n.`else` ?: return 0
        return if (elseBranch is KtIfExpression) 0 else 1 // `else if` charges via isFlatIncrement
    }

    override fun isArgumentClosure(n: PsiElement): Boolean =
        // Trailing lambdas sit in a KtLambdaArgument (a KtValueArgument subtype); parenthesized
        // closures in a plain KtValueArgument — both are "a closure handed to a call".
        n is KtLambdaExpression && n.parent is KtValueArgument

    override fun isLogicalSequence(n: PsiElement): Boolean {
        if (n !is KtBinaryExpression) return false
        val op = n.operationToken
        if (op != KtTokens.ANDAND && op != KtTokens.OROR) return false
        // Count once per run: only the topmost operator of a like-operator chain.
        val parent = n.parent
        return !(parent is KtBinaryExpression && parent.operationToken == op)
    }

    override fun isLoopOrBranch(n: PsiElement): Boolean =
        n is KtIfExpression || n is KtForExpression || n is KtWhileExpression ||
            n is KtDoWhileExpression || n is KtWhenExpression ||
            (n is KtBinaryExpression && (n.operationToken == KtTokens.ANDAND || n.operationToken == KtTokens.OROR))

    // Deliberate asymmetry with isDecisionPoint: the `else` entry IS an execution path (npath counts
    // paths), while McCabe counts decisions (else is the absence of one) — so npath includes it and
    // cyclomatic excludes it. Mirrors the Java classifier's `default` label handling.
    override fun npathMultiplier(n: PsiElement): Int =
        when (n) {
            is KtWhenExpression -> n.entries.size.coerceAtLeast(2)
            else -> 2
        }

    override fun isScopeFunctionInvocation(n: PsiElement): Boolean {
        if (n !is KtCallExpression) return false
        val callee = (n.calleeExpression as? KtSimpleNameExpression)?.getReferencedName() ?: return false
        return callee in scopeFunctionNames && n.lambdaArguments.isNotEmpty()
    }

    override fun parameters(fn: PsiElement): List<Param> {
        val function = fn as? KtFunction ?: return emptyList()
        return function.valueParameters.map { it.toParam() }
    }

    override fun annotations(decl: PsiElement): List<String> {
        val owner = decl as? KtModifierListOwner ?: return emptyList()
        return owner.annotationEntries.mapNotNull { it.shortName?.asString() }
    }

    override fun modifiers(decl: PsiElement): Modifiers {
        val owner = decl as? KtModifierListOwner ?: return Modifiers.PUBLIC

        fun has(token: org.jetbrains.kotlin.lexer.KtModifierKeywordToken) = owner.hasModifier(token)
        val visibility =
            when {
                has(KtTokens.PRIVATE_KEYWORD) -> Visibility.PRIVATE
                has(KtTokens.PROTECTED_KEYWORD) -> Visibility.PROTECTED
                has(KtTokens.INTERNAL_KEYWORD) -> Visibility.INTERNAL
                else -> Visibility.PUBLIC // Kotlin default visibility is public
            }
        return Modifiers(
            visibility = visibility,
            isAbstract = has(KtTokens.ABSTRACT_KEYWORD),
            isOpen = has(KtTokens.OPEN_KEYWORD),
            isFinal = !has(KtTokens.OPEN_KEYWORD) && !has(KtTokens.ABSTRACT_KEYWORD),
            isSealed = has(KtTokens.SEALED_KEYWORD),
            isData = has(KtTokens.DATA_KEYWORD),
            isOverride = has(KtTokens.OVERRIDE_KEYWORD),
            isCompanion = has(KtTokens.COMPANION_KEYWORD),
            isInner = has(KtTokens.INNER_KEYWORD),
            raw = owner.modifierList?.text?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        )
    }

    override fun calledSymbols(scope: PsiElement): List<SymbolRef> =
        scope.collectDescendantsOfType<KtCallExpression>().mapNotNull { call ->
            (call.calleeExpression as? KtSimpleNameExpression)?.getReferencedName()?.let {
                SymbolRef(name = it, container = null, resolution = Resolution.NAME_BASED)
            }
        }.distinctBy { it.key }

    override fun referencedTypes(scope: PsiElement): List<TypeRef> =
        scope.collectDescendantsOfType<KtTypeReference>().mapNotNull { ref ->
            val raw = ref.typeElement?.text ?: return@mapNotNull null
            // Skip builtin primitives (the cross-language analogue of Java primitives — not class couplings),
            // and normalize to the bare class name so `Dep1?` / `List<Dep1>` key the SAME as their plain class
            // (matching the resolved classifier); otherwise name-based mode over-counts couplings (CK 1994).
            if (isKotlinPrimitiveType(raw)) {
                null
            } else {
                simpleTypeName(raw).takeIf { it.isNotBlank() }
                    ?.let { TypeRef(name = it, qualifiedName = null, packageName = null, resolution = Resolution.NAME_BASED) }
            }
        }.distinctBy { it.key }

    override fun outgoingRefNames(scope: PsiElement): List<String> {
        val calls =
            scope.collectDescendantsOfType<KtCallExpression>().mapNotNull { call ->
                (call.calleeExpression as? KtSimpleNameExpression)?.getReferencedName()
            }
        val types =
            scope.collectDescendantsOfType<KtTypeReference>()
                .mapNotNull { it.typeElement?.text }
                .filterNot { isKotlinPrimitiveType(it) }
                .map { simpleTypeName(it) }
                .filter { it.isNotBlank() }
        return calls + types + operatorConventionNames(scope)
    }

    override fun supertypes(type: PsiElement): List<TypeRef> {
        // The supertype entries live under a KtSuperTypeList, i.e. grandchildren of the class — a
        // direct getChildrenOfType for KtSuperTypeListEntry finds none. The PSI accessor walks them.
        val entries = (type as? KtClassOrObject)?.superTypeListEntries ?: return emptyList()
        return entries.mapNotNull { entry ->
            entry.typeReference?.text?.let {
                TypeRef(name = it, qualifiedName = null, packageName = null, resolution = Resolution.NAME_BASED)
            }
        }
    }

    override fun fieldAccesses(scope: PsiElement): Set<String> =
        scope.collectDescendantsOfType<KtNameReferenceExpression>()
            .map { it.getReferencedName() }
            .toSet()

    // Same walk as fieldAccesses today, but a DISTINCT contract: fieldAccesses may later narrow to
    // resolved instance-field accesses (LCOM4) while reachability must keep seeing every name read.
    // Operator-convention names ride along: `a[i]`/`a + b`/`for (x in y)`/`val (a, b)`/`by` carry NO
    // name reference to the operator function they invoke, so a user-defined `operator fun get`/
    // `plus`/`iterator`/`componentN`/`getValue` reached only through the operator form would be
    // reported unused (and `--apply` could delete it). This channel only ever ADDS reachability and
    // never feeds the resolution stamp — the safe direction. One walk collects both channels: this
    // runs once per declaration in the unused sweep, so a second full traversal would be hot.
    override fun referencedNames(scope: PsiElement): Set<String> =
        buildSet {
            for (n in sequenceOf(scope) + descendants(scope)) {
                if (n is KtNameReferenceExpression) add(n.getReferencedName())
                addConventionNamesOf(n)
            }
        }

    /**
     * The convention-function names invoked by every operator form in [scope] (Kotlin language spec
     * operator conventions). Name-based by design: where the form is ambiguous without resolution
     * (`a[i]` read vs write, `a += b` as `plusAssign` vs `plus`), BOTH candidates are emitted —
     * over-connecting errs toward keeping live code.
     */
    protected fun operatorConventionNames(scope: PsiElement): List<String> =
        buildList {
            for (n in (sequenceOf(scope) + descendants(scope))) {
                addConventionNamesOf(n)
            }
        }

    override fun tokens(scope: PsiElement): List<Token> =
        leaves(scope).mapNotNull { leaf ->
            if (leaf is PsiWhiteSpace || leaf is PsiComment) return@mapNotNull null
            val kind =
                when {
                    leaf.parent is KtConstantExpression -> TokenKind.OPERAND
                    // A string literal's content leaf sits under a KtLiteralStringTemplateEntry, NOT directly
                    // under the KtStringTemplateExpression; the only direct children of the template are the
                    // surrounding quote delimiters. Counting those quotes as operands (and the content as an
                    // operator) inverts Halstead for every function with a string literal.
                    leaf.parent is KtLiteralStringTemplateEntry -> TokenKind.OPERAND
                    leaf.parent is KtStringTemplateExpression -> TokenKind.OPERATOR // quote delimiters
                    leaf is KtNameReferenceExpression || isIdentifier(leaf) -> TokenKind.OPERAND
                    else -> TokenKind.OPERATOR
                }
            Token(leaf.text, kind)
        }.toList()

    override fun children(n: PsiElement): List<PsiElement> = generateSequence(n.firstChild) { it.nextSibling }.toList()

    override fun text(n: PsiElement): String = n.text

    private fun leaves(root: PsiElement): Sequence<PsiElement> =
        sequence {
            val children = generateSequence(root.firstChild) { it.nextSibling }.toList()
            if (children.isEmpty()) {
                yield(root)
            } else {
                for (child in children) yieldAll(leaves(child))
            }
        }
}

/** Kotlin's builtin primitive-like types — the cross-language analogue of Java primitives, excluded from
 *  coupling (CBO) so the metric counts class-to-class coupling consistently across both languages. */
internal val KOTLIN_PRIMITIVE_TYPE_NAMES =
    setOf("Int", "Long", "Short", "Byte", "Char", "Boolean", "Float", "Double", "Unit")

private val KOTLIN_PRIMITIVE_FQNS = KOTLIN_PRIMITIVE_TYPE_NAMES.map { "kotlin.$it" }.toSet()

/** The bare class name of a type-ref text: drops a generic argument list and a trailing nullability `?`
 *  so a name-based coupling key (`Dep1?`, `List<Dep1>`) matches the resolved classifier's plain class. */
internal fun simpleTypeName(text: String): String = text.substringBefore('<').removeSuffix("?").trim()

/** True for a Kotlin builtin primitive, accepting a simple name, a nullable form, a generic head, or a
 *  resolved `kotlin.X` key. A dotted input is matched EXACTLY (so a user `com.foo.Int` is not excluded). */
internal fun isKotlinPrimitiveType(typeNameOrKey: String): Boolean {
    val normalized = typeNameOrKey.substringBefore('<').removeSuffix("?").trim()
    return if ('.' in normalized) normalized in KOTLIN_PRIMITIVE_FQNS else normalized in KOTLIN_PRIMITIVE_TYPE_NAMES
}
