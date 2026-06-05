package dev.ktrics.frontend.kotlin

import com.intellij.psi.PsiElement
import dev.ktrics.ir.Param
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpressionWithLabel
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtWhenEntry

/** Kotlin-PSI shape predicates and lowering helpers shared by the classifier dispatch. */

internal fun KtParameter.toParam(): Param {
    val typeText = typeReference?.text ?: "Any"
    return Param(
        name = name ?: "_",
        typeName = typeText,
        hasDefault = hasDefaultValue(),
        isVararg = isVarArg,
        isBoolean = typeText.removeSuffix("?").substringAfterLast('.') == "Boolean",
    )
}

/** The `else if` link of an if-chain: an if that IS its parent if's else branch (flat per spec). */
internal fun isElseIfBranch(n: PsiElement): Boolean {
    if (n !is KtIfExpression) return false
    // The else branch sits inside a container node, so the owning if is the grandparent.
    val container = n.parent as? KtContainerNodeForControlStructureBody ?: return false
    return (container.parent as? KtIfExpression)?.`else` === n
}

internal fun isLabeledJump(n: PsiElement): Boolean =
    (n is KtBreakExpression || n is KtContinueExpression) && (n as KtExpressionWithLabel).getLabelName() != null

// A `when` entry is a decision point unless it is the `else` branch. Use the structural `isElse` flag,
// not a text prefix — `startsWith("else")` wrongly excluded entries whose condition began with an
// identifier like `elseStatus`, undercounting cyclomatic complexity.
internal fun isWhenEntryWithCondition(n: PsiElement): Boolean = n is KtWhenEntry && !n.isElse

internal fun isIdentifier(leaf: PsiElement): Boolean = (leaf.node?.elementType == KtTokens.IDENTIFIER)

/** Appends the convention name(s) one PSI node's operator form invokes, if it is one. */
internal fun MutableCollection<String>.addConventionNamesOf(n: PsiElement) {
    when (n) {
        is KtArrayAccessExpression -> {
            add("get")
            add("set")
        }
        is KtBinaryExpression -> BINARY_OPERATOR_CONVENTIONS[n.operationToken]?.let(::addAll)
        is KtPrefixExpression ->
            PREFIX_OPERATOR_CONVENTIONS[n.operationReference.getReferencedNameElementType()]?.let(::add)
        is KtPostfixExpression ->
            POSTFIX_OPERATOR_CONVENTIONS[n.operationReference.getReferencedNameElementType()]?.let(::add)
        is KtForExpression -> addAll(ITERATION_CONVENTIONS)
        is KtDestructuringDeclaration -> n.entries.forEachIndexed { i, _ -> add("component${i + 1}") }
        is KtPropertyDelegate -> addAll(DELEGATE_CONVENTIONS)
        is KtCallExpression ->
            // `obj()` may be an `invoke` convention call — and without resolution a callee that
            // LOOKS like a simple name can still be a variable (`val a = Vec(); a()`). Emit
            // `invoke` for every call: it only ever affects declarations literally named `invoke`
            // (i.e. the operators), in the safe over-connecting direction.
            add("invoke")
        else -> {}
    }
}
