package dev.ktrics.langapi

import com.intellij.psi.PsiElement
import dev.ktrics.ir.Modifiers
import dev.ktrics.ir.Param
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SymbolRef
import dev.ktrics.ir.Token
import dev.ktrics.ir.TypeRef
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/**
 * The interface default methods are language-free helpers calculators rely on: DFS descendants, the
 * decision-weight fallback, and the deduped outgoing-name fallback.
 * A stub PsiElement (never dereferenced) plus a configurable classifier exercises them with no session.
 */
class NodeClassifierDefaultsTest {
    /**
     * A unique stub PsiElement; the defaults only use it as an identity key into the maps below.
     * equals/hashCode/toString resolve by identity so the nodes compare and print as themselves.
     */
    private fun stubNode(): PsiElement =
        Proxy.newProxyInstance(
            NodeClassifier::class.java.classLoader,
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

    /**
     * A classifier that leaves [decisionWeight], [descendants] and [outgoingRefNames] at their
     * interface defaults; only the primitives those defaults call are backed by canned per-node data.
     */
    private inner class TreeClassifier : NodeClassifier {
        private val childMap = IdentityHashMap<PsiElement, List<PsiElement>>()
        private val decisions = java.util.Collections.newSetFromMap(IdentityHashMap<PsiElement, Boolean>())
        private val callNames = IdentityHashMap<PsiElement, List<String>>()
        private val typeNames = IdentityHashMap<PsiElement, List<String>>()

        fun node(
            children: List<PsiElement> = emptyList(),
            decision: Boolean = false,
            calls: List<String> = emptyList(),
            types: List<String> = emptyList(),
        ): PsiElement {
            val n = stubNode()
            childMap[n] = children
            if (decision) decisions.add(n)
            callNames[n] = calls
            typeNames[n] = types
            return n
        }

        override fun children(n: PsiElement): List<PsiElement> = childMap[n].orEmpty()

        override fun isDecisionPoint(n: PsiElement): Boolean = n in decisions

        override fun calledSymbols(scope: PsiElement): List<SymbolRef> =
            callNames[scope].orEmpty().map { SymbolRef(it, null, Resolution.NAME_BASED) }

        override fun referencedTypes(scope: PsiElement): List<TypeRef> =
            typeNames[scope].orEmpty().map { TypeRef(it, null, null, Resolution.NAME_BASED) }

        // --- Unused by the defaults under test ---
        override fun isNestingBoundary(n: PsiElement): Boolean = false

        override fun isLogicalSequence(n: PsiElement): Boolean = false

        override fun isLoopOrBranch(n: PsiElement): Boolean = false

        override fun npathMultiplier(n: PsiElement): Int = 1

        override fun parameters(fn: PsiElement): List<Param> = emptyList()

        override fun annotations(decl: PsiElement): List<String> = emptyList()

        override fun modifiers(decl: PsiElement): Modifiers = Modifiers.PUBLIC

        override fun supertypes(type: PsiElement): List<TypeRef> = emptyList()

        override fun fieldAccesses(scope: PsiElement): Set<String> = emptySet()

        override fun tokens(scope: PsiElement): List<Token> = emptyList()

        override fun text(n: PsiElement): String = ""
    }

    @Test
    fun `descendants walks depth-first in pre-order, excluding the root`() {
        val c = TreeClassifier()
        val a1 = c.node()
        val a = c.node(children = listOf(a1))
        val b = c.node()
        val root = c.node(children = listOf(a, b))

        // Pre-order DFS: a, then a's subtree, then b. Root itself is not yielded.
        c.descendants(root).toList() shouldContainExactly listOf(a, a1, b)
    }

    @Test
    fun `descendants of a leaf is empty`() {
        val c = TreeClassifier()
        c.descendants(c.node()).toList() shouldContainExactly emptyList()
    }

    @Test
    fun `decisionWeight defaults to one at a decision point and zero elsewhere`() {
        val c = TreeClassifier()
        c.decisionWeight(c.node(decision = true)) shouldBe 1
        c.decisionWeight(c.node(decision = false)) shouldBe 0
    }

    @Test
    fun `outgoingRefNames defaults to called symbol names followed by referenced type names`() {
        val c = TreeClassifier()
        val scope = c.node(calls = listOf("doWork", "log"), types = listOf("Service", "Logger"))
        // The default concatenates the two deduped lists (call names first), losing multiplicity.
        c.outgoingRefNames(scope) shouldContainExactly listOf("doWork", "log", "Service", "Logger")
    }

    @Test
    fun `referencedNames defaults to empty`() {
        // The value-read channel is opt-in; a classifier that doesn't override adds no reachability edges.
        TreeClassifier().referencedNames(stubNode()) shouldBe emptySet<String>()
    }

    @Test
    fun `isScopeFunctionInvocation defaults to false`() {
        // Java never overrides this; the metric is Kotlin-scoped, so the default must be false.
        TreeClassifier().isScopeFunctionInvocation(stubNode()) shouldBe false
    }
}
