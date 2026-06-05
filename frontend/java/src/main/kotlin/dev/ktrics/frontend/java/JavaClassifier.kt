package dev.ktrics.frontend.java

import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiBreakStatement
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiContinueStatement
import com.intellij.psi.PsiDoWhileStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiSwitchLabelStatementBase
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.PsiWhileStatement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import dev.ktrics.ir.Modifiers
import dev.ktrics.ir.Param
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SymbolRef
import dev.ktrics.ir.Token
import dev.ktrics.ir.TokenKind
import dev.ktrics.ir.TypeRef
import dev.ktrics.ir.Visibility
import dev.ktrics.langapi.NodeClassifier

/**
 * Java dispatch of the [NodeClassifier]. Classic + enhanced switch, ternary, `&&`/`||`,
 * `catch`, lambda nesting. Java's `a && b && c` is one polyadic node carrying two operators, so
 * [decisionWeight] returns the operator count rather than 1 (so chained conditions aren't undercounted).
 */
open class JavaClassifier : NodeClassifier {
    override fun isDecisionPoint(n: PsiElement): Boolean =
        when (n) {
            is PsiIfStatement, is PsiForStatement, is PsiForeachStatement,
            is PsiWhileStatement, is PsiDoWhileStatement, is PsiCatchSection,
            is PsiConditionalExpression,
            -> true
            is PsiSwitchLabelStatementBase -> !n.isDefaultCase()
            is PsiPolyadicExpression -> isLogicalOperator(n)
            else -> false
        }

    override fun decisionWeight(n: PsiElement): Int =
        when {
            n is PsiPolyadicExpression && isLogicalOperator(n) -> (n.operands.size - 1).coerceAtLeast(1)
            isDecisionPoint(n) -> 1
            else -> 0
        }

    override fun isNestingBoundary(n: PsiElement): Boolean =
        when (n) {
            is PsiIfStatement, is PsiForStatement, is PsiForeachStatement, is PsiWhileStatement,
            is PsiDoWhileStatement, is PsiSwitchStatement, is PsiCatchSection,
            -> true
            else -> false
        }

    // A lambda raises the nesting level WITHOUT a B1 increment (SonarSource: "method-like structures").
    override fun isNestingOnlyBoundary(n: PsiElement): Boolean = n is PsiLambdaExpression

    override fun isFlatIncrement(n: PsiElement): Boolean = isElseIfBranch(n) || isLabeledJump(n)

    override fun elseIncrement(n: PsiElement): Int {
        if (n !is PsiIfStatement) return 0
        val elseBranch = n.elseBranch ?: return 0
        return if (elseBranch is PsiIfStatement) 0 else 1 // `else if` charges via isFlatIncrement
    }

    override fun isArgumentClosure(n: PsiElement): Boolean = n is PsiLambdaExpression && n.parent is PsiExpressionList

    override fun isLogicalSequence(n: PsiElement): Boolean = n is PsiPolyadicExpression && isLogicalOperator(n)

    override fun isLoopOrBranch(n: PsiElement): Boolean =
        when (n) {
            is PsiIfStatement, is PsiForStatement, is PsiForeachStatement, is PsiWhileStatement,
            is PsiDoWhileStatement, is PsiSwitchStatement, is PsiConditionalExpression,
            -> true
            is PsiPolyadicExpression -> isLogicalOperator(n)
            else -> false
        }

    // Deliberate asymmetry with isDecisionPoint: the `default` label IS an execution path (npath
    // counts paths), while McCabe counts decisions (default is the absence of one) — so npath
    // includes it and cyclomatic excludes it. Mirrors the Kotlin classifier's `else` entry handling.
    override fun npathMultiplier(n: PsiElement): Int =
        when (n) {
            is PsiSwitchStatement -> (n.body?.statements?.count { it is PsiSwitchLabelStatementBase } ?: 1).coerceAtLeast(2)
            is PsiPolyadicExpression -> n.operands.size.coerceAtLeast(2)
            else -> 2
        }

    override fun parameters(fn: PsiElement): List<Param> {
        val method = fn as? PsiMethod ?: return emptyList()
        return method.parameterList.parameters.map { it.toParam() }
    }

    override fun annotations(decl: PsiElement): List<String> {
        val owner = decl as? PsiModifierListOwner ?: return emptyList()
        return owner.annotations.mapNotNull { it.qualifiedName?.substringAfterLast('.') ?: it.nameReferenceElement?.referenceName }
    }

    override fun modifiers(decl: PsiElement): Modifiers {
        val owner = decl as? PsiModifierListOwner ?: return Modifiers.PUBLIC

        fun has(m: String) = owner.hasModifierProperty(m)
        val visibility =
            when {
                has(PsiModifier.PUBLIC) -> Visibility.PUBLIC
                has(PsiModifier.PROTECTED) -> Visibility.PROTECTED
                has(PsiModifier.PRIVATE) -> Visibility.PRIVATE
                else -> Visibility.PACKAGE_PRIVATE
            }
        // Java's `@Override` is an annotation, not a modifier keyword, so isOverride must be derived: a
        // method that overrides/implements a supertype member (findSuperMethods, e.g. Runnable.run) OR
        // carries @Override. Unused-reachability seeds overrides as roots, so missing this would falsely
        // flag framework-invoked Java overrides as unused.
        val isOverride =
            (decl as? PsiMethod)?.let { method ->
                // Check the cheap, syntactic @Override first (the common case); only then the supertype
                // lookup, which is guarded — findSuperMethods triggers hierarchy resolution and can throw
                // (e.g. IndexNotReadyException) on the per-method lowering path, which must not abort it.
                method.annotations.any {
                    it.qualifiedName == "java.lang.Override" || it.nameReferenceElement?.referenceName == "Override"
                } || runCatching { method.findSuperMethods().isNotEmpty() }.getOrDefault(false)
            } ?: false
        return Modifiers(
            visibility = visibility,
            isAbstract = has(PsiModifier.ABSTRACT),
            isFinal = has(PsiModifier.FINAL),
            isStatic = has(PsiModifier.STATIC),
            isOverride = isOverride,
            raw = owner.modifierList?.text?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        )
    }

    override fun calledSymbols(scope: PsiElement): List<SymbolRef> =
        PsiTreeUtil.collectElementsOfType(scope, PsiMethodCallExpression::class.java).map { call ->
            SymbolRef(call.methodExpression.referenceName ?: "?", container = null, resolution = Resolution.NAME_BASED)
        }.distinctBy { it.key }

    override fun referencedTypes(scope: PsiElement): List<TypeRef> =
        PsiTreeUtil.collectElementsOfType(scope, PsiTypeElement::class.java)
            // A primitive (int/boolean/void) is a fully-known builtin, not coupling to another class — and
            // it must NOT be treated as an unresolved reference (CBO counts classes; CK 1994). The bare name
            // (generics dropped) keys `List<Dep>` the same as `List`, matching the resolved classifier.
            .filterNot { it.type is PsiPrimitiveType }
            .mapNotNull { te ->
                te.type.presentableText.substringBefore('<').trim().takeIf { it.isNotBlank() }?.let {
                    TypeRef(name = it, qualifiedName = null, packageName = null, resolution = Resolution.NAME_BASED)
                }
            }.distinctBy { it.key }

    override fun outgoingRefNames(scope: PsiElement): List<String> {
        val calls =
            PsiTreeUtil.collectElementsOfType(scope, PsiMethodCallExpression::class.java)
                .mapNotNull { it.methodExpression.referenceName }
        val types =
            PsiTreeUtil.collectElementsOfType(scope, PsiTypeElement::class.java)
                .filterNot { it.type is PsiPrimitiveType }
                .map { it.type.presentableText.substringBefore('<').trim() }
                .filter { it.isNotBlank() }
        return calls + types
    }

    override fun supertypes(type: PsiElement): List<TypeRef> {
        // extends/implements references live under PsiReferenceList children, not as direct children
        // of the class — a direct getChildrenOfType for the reference element finds none.
        val psiClass = type as? PsiClass ?: return emptyList()
        val refs =
            listOfNotNull(psiClass.extendsList, psiClass.implementsList)
                .flatMap { it.referenceElements.toList() }
        return refs.mapNotNull { ref ->
            ref.referenceName?.let {
                TypeRef(name = it, qualifiedName = ref.qualifiedName, packageName = null, resolution = Resolution.NAME_BASED)
            }
        }
    }

    override fun fieldAccesses(scope: PsiElement): Set<String> =
        PsiTreeUtil.collectElementsOfType(scope, PsiReferenceExpression::class.java)
            .mapNotNull { it.referenceName }
            .toSet()

    // PsiJavaCodeReferenceElement (the supertype of PsiReferenceExpression) also covers class
    // references outside expression position (`Foo.class`, qualifiers of `new Foo.Bar()`), which a
    // field/method walk misses. A DISTINCT contract from fieldAccesses: that one may later narrow to
    // resolved instance-field accesses (LCOM4) while reachability must keep seeing every name read.
    override fun referencedNames(scope: PsiElement): Set<String> =
        PsiTreeUtil.collectElementsOfType(scope, PsiJavaCodeReferenceElement::class.java)
            .mapNotNull { it.referenceName }
            .toSet()

    override fun tokens(scope: PsiElement): List<Token> =
        leaves(scope).mapNotNull { leaf ->
            if (leaf is PsiWhiteSpace || leaf is PsiComment) return@mapNotNull null
            val kind =
                when {
                    leaf is PsiIdentifier -> TokenKind.OPERAND
                    leaf.parent is PsiLiteralExpression -> TokenKind.OPERAND
                    else -> TokenKind.OPERATOR
                }
            Token(leaf.text, kind)
        }.toList()

    override fun children(n: PsiElement): List<PsiElement> = generateSequence(n.firstChild) { it.nextSibling }.toList()

    override fun text(n: PsiElement): String = n.text

    // --- Java PSI helpers ---

    private fun isLogicalOperator(n: PsiPolyadicExpression): Boolean =
        n.operationTokenType == JavaTokenType.ANDAND || n.operationTokenType == JavaTokenType.OROR

    private fun leaves(root: PsiElement): Sequence<PsiElement> =
        sequence {
            val children = generateSequence(root.firstChild) { it.nextSibling }.toList()
            if (children.isEmpty()) yield(root) else for (child in children) yieldAll(leaves(child))
        }
}

private fun PsiParameter.toParam(): Param {
    val typeText = type.presentableText
    return Param(
        name = name,
        typeName = typeText,
        // Java has no default parameters
        hasDefault = false,
        isVararg = isVarArgs,
        isBoolean = typeText == "boolean" || typeText == "Boolean",
    )
}

/** The `else if` link of an if-chain: an if that IS its parent if's else branch (flat per spec). */
private fun isElseIfBranch(n: PsiElement): Boolean = n is PsiIfStatement && (n.parent as? PsiIfStatement)?.elseBranch === n

private fun isLabeledJump(n: PsiElement): Boolean =
    (n is PsiBreakStatement && n.labelIdentifier != null) || (n is PsiContinueStatement && n.labelIdentifier != null)
