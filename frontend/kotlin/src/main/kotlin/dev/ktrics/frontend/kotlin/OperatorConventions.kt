package dev.ktrics.frontend.kotlin

import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.lexer.KtTokens

/**
 * Operator token → convention-function name(s) (Kotlin language spec, "Operator overloading").
 * Compound assignments emit BOTH candidates (`a += b` compiles to `plusAssign` OR `plus` + assign,
 * depending on what the receiver declares) — over-connecting is the safe direction for reachability.
 * `===`/`!==` (identity), `&&`/`||`/elvis (built-ins, not convention calls) are deliberately absent.
 */
internal val BINARY_OPERATOR_CONVENTIONS: Map<IElementType, List<String>> =
    mapOf(
        KtTokens.PLUS to listOf("plus"),
        KtTokens.MINUS to listOf("minus"),
        KtTokens.MUL to listOf("times"),
        KtTokens.DIV to listOf("div"),
        KtTokens.PERC to listOf("rem"),
        KtTokens.RANGE to listOf("rangeTo"),
        KtTokens.RANGE_UNTIL to listOf("rangeUntil"),
        KtTokens.IN_KEYWORD to listOf("contains"),
        KtTokens.NOT_IN to listOf("contains"),
        KtTokens.EQEQ to listOf("equals"),
        KtTokens.EXCLEQ to listOf("equals"),
        KtTokens.LT to listOf("compareTo"),
        KtTokens.GT to listOf("compareTo"),
        KtTokens.LTEQ to listOf("compareTo"),
        KtTokens.GTEQ to listOf("compareTo"),
        KtTokens.PLUSEQ to listOf("plusAssign", "plus"),
        KtTokens.MINUSEQ to listOf("minusAssign", "minus"),
        KtTokens.MULTEQ to listOf("timesAssign", "times"),
        KtTokens.DIVEQ to listOf("divAssign", "div"),
        KtTokens.PERCEQ to listOf("remAssign", "rem"),
    )

internal val PREFIX_OPERATOR_CONVENTIONS: Map<IElementType, String> =
    mapOf(
        KtTokens.EXCL to "not",
        KtTokens.MINUS to "unaryMinus",
        KtTokens.PLUS to "unaryPlus",
        KtTokens.PLUSPLUS to "inc",
        KtTokens.MINUSMINUS to "dec",
    )

internal val POSTFIX_OPERATOR_CONVENTIONS: Map<IElementType, String> =
    mapOf(
        KtTokens.PLUSPLUS to "inc",
        KtTokens.MINUSMINUS to "dec",
    )

/** `for (x in y)` desugars to `y.iterator()` + `hasNext()` + `next()`. */
internal val ITERATION_CONVENTIONS = listOf("iterator", "hasNext", "next")

/** `val x by d` desugars to `d.getValue(...)` (+ `setValue` for var, `provideDelegate` when declared). */
internal val DELEGATE_CONVENTIONS = listOf("getValue", "setValue", "provideDelegate")
