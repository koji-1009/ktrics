package dev.ktrics.testsupport

import com.intellij.psi.PsiElement
import java.lang.reflect.Proxy

/**
 * A unique stub [PsiElement] for pure-logic tests. The code under test only ever passes an IR node
 * back to a [dev.ktrics.langapi.NodeClassifier]; it never dereferences the node. So a `Proxy` that
 * answers every interface method with a harmless default — and resolves identity via
 * `equals`/`hashCode`/`toString` — is enough to stand in for a real PSI element, with no live session.
 */
fun stubNode(): PsiElement =
    Proxy.newProxyInstance(
        PsiElement::class.java.classLoader,
        arrayOf(PsiElement::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "stub@" + System.identityHashCode(proxy)
            else ->
                when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    else -> null
                }
        }
    } as PsiElement
