package dev.ktrics.testsupport

import com.intellij.psi.PsiElement
import dev.ktrics.ir.Modifiers
import dev.ktrics.ir.Param
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SymbolRef
import dev.ktrics.ir.Token
import dev.ktrics.ir.TypeRef
import dev.ktrics.langapi.NodeClassifier
import java.util.IdentityHashMap

/**
 * A [NodeClassifier] backed by canned, per-node data registered by identity. Promoted from `unused`'s
 * local fixture so `metric`, `callgraph`, and `engine` graph tests can drive the reference/cohesion
 * machinery with no live K2/PSI session. Every node it hands out is a [stubNode]; the data attached
 * to it is looked up by reference identity.
 */
@Suppress("TooManyFunctions") // Implements the full NodeClassifier surface; the method count is the interface's.
class FakeClassifier : NodeClassifier {
    private val calls = IdentityHashMap<PsiElement, List<String>>()
    private val types = IdentityHashMap<PsiElement, List<String>>()
    private val supertypeNames = IdentityHashMap<PsiElement, List<String>>()
    private val fields = IdentityHashMap<PsiElement, Set<String>>()
    private val resolution = IdentityHashMap<PsiElement, Resolution>()

    /** Registers a node's outgoing references and returns it (used by the IR builders). */
    fun register(
        calls: List<String> = emptyList(),
        types: List<String> = emptyList(),
        supertypes: List<String> = emptyList(),
        fieldAccesses: Set<String> = emptySet(),
        resolution: Resolution = Resolution.NAME_BASED,
    ): PsiElement {
        val n = stubNode()
        this.calls[n] = calls
        this.types[n] = types
        this.supertypeNames[n] = supertypes
        this.fields[n] = fieldAccesses
        this.resolution[n] = resolution
        return n
    }

    private fun res(scope: PsiElement) = resolution[scope] ?: Resolution.NAME_BASED

    override fun outgoingRefNames(scope: PsiElement): List<String> = calls[scope].orEmpty() + types[scope].orEmpty()

    override fun calledSymbols(scope: PsiElement): List<SymbolRef> = calls[scope].orEmpty().map { SymbolRef(it, null, res(scope)) }

    override fun referencedTypes(scope: PsiElement): List<TypeRef> = types[scope].orEmpty().map { TypeRef(it, null, null, res(scope)) }

    override fun supertypes(type: PsiElement): List<TypeRef> = supertypeNames[type].orEmpty().map { TypeRef(it, it, null, res(type)) }

    override fun fieldAccesses(scope: PsiElement): Set<String> = fields[scope].orEmpty()

    override fun parameters(fn: PsiElement): List<Param> = emptyList()

    override fun annotations(decl: PsiElement): List<String> = emptyList()

    override fun modifiers(decl: PsiElement): Modifiers = Modifiers.PUBLIC

    override fun tokens(scope: PsiElement): List<Token> = emptyList()

    override fun children(n: PsiElement): List<PsiElement> = emptyList()

    override fun text(n: PsiElement): String = ""

    override fun isDecisionPoint(n: PsiElement): Boolean = false

    override fun isNestingBoundary(n: PsiElement): Boolean = false

    override fun isLogicalSequence(n: PsiElement): Boolean = false

    override fun isLoopOrBranch(n: PsiElement): Boolean = false

    override fun npathMultiplier(n: PsiElement): Int = 2
}
