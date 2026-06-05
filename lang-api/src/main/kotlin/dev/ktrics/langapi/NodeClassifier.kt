package dev.ktrics.langapi

import com.intellij.psi.PsiElement
import dev.ktrics.ir.Modifiers
import dev.ktrics.ir.Param
import dev.ktrics.ir.SymbolRef
import dev.ktrics.ir.Token
import dev.ktrics.ir.TypeRef

/**
 * The one abstraction over [PsiElement] that metric calculators see.
 *
 * Calculators NEVER special-case a language — they walk `com.intellij.psi.PsiElement` (the common
 * supertype of Kotlin `KtElement` and Java PSI) through this classifier. Per-language counting
 * nuances live entirely inside the impls (`KotlinClassifier`, `JavaClassifier`); every deviation
 * from a cited paper is documented in doc/calibration.md.
 *
 * `calledSymbols`/`referencedTypes`/`supertypes` return resolved references from the shared symbol
 * space, so coupling/cohesion metrics are written once and get resolved Kotlin↔Java edges for free.
 * Initially they receive syntactic/name-based data; resolution is turned on later via `analyze {}`
 * with no calculator rewrite.
 */
interface NodeClassifier {
    // --- Control-flow shape (function-level metrics) ---

    /** if/for/while/do/case/catch/&&/||/?:/elvis → +1 cyclomatic (McCabe). */
    fun isDecisionPoint(n: PsiElement): Boolean

    /**
     * The cyclomatic increment [n] itself contributes. Defaults to 1 when [isDecisionPoint], else 0,
     * but a language that *groups* operators (Java's polyadic `a && b && c` is one node carrying two
     * `&&`) overrides this to return the operator count, so chained conditions are not undercounted.
     */
    fun decisionWeight(n: PsiElement): Int = if (isDecisionPoint(n)) 1 else 0

    /** Increments cognitive nesting depth (B2) AND charges a B1 increment: if/loops/when/catch. */
    fun isNestingBoundary(n: PsiElement): Boolean

    /**
     * Increments cognitive nesting depth WITHOUT a B1 increment (SonarSource: "nested methods and
     * method-like structures such as lambdas" raise the nesting level but are not themselves control
     * structures): lambdas and local functions.
     */
    fun isNestingOnlyBoundary(n: PsiElement): Boolean = false

    /**
     * Charges a FLAT B1 increment (+1, no nesting penalty, and no extra nesting for its children):
     * the `else if` link of an if-chain, and a labeled `break`/`continue` (SonarSource's per-branch
     * rule — a flat chain reads linearly, so only the head pays the nesting price). Checked before
     * [isNestingBoundary] by the cognitive walk, so an `else if` (which is still an if node) stays flat.
     */
    fun isFlatIncrement(n: PsiElement): Boolean = false

    /**
     * The flat increment an `else` branch contributes through its owning `if` node: 1 when [n] is an
     * if with a plain (non-`else if`) else branch, else 0. The else keyword has no node of its own in
     * either PSI, so the charge rides on the if (SonarSource charges `else` +1, no nesting).
     */
    fun elseIncrement(n: PsiElement): Int = 0

    /**
     * A closure passed as a call argument (`items.forEach { … }`, `test("x") { … }`) — the shape the
     * test-DSL discount skips on test files: registration callbacks are data handed to the DSL, not
     * control flow of the enclosing function. False for everything else.
     */
    fun isArgumentClosure(n: PsiElement): Boolean = false

    /** A run of `&&`/`||` for cognitive B3 (a *sequence* of like operators counts once). */
    fun isLogicalSequence(n: PsiElement): Boolean

    /** Loop or branch node — an npath multiplicand (Nejmeh). */
    fun isLoopOrBranch(n: PsiElement): Boolean

    /** The increment a single decision node contributes to npath, given its branch arity. */
    fun npathMultiplier(n: PsiElement): Int

    /**
     * A Kotlin scope-function invocation (`let`/`run`/`apply`/`also`/`with`) whose trailing lambda
     * introduces a nesting level for `scope-function-nesting` (Kotlin-only). Java returns
     * false — the metric is scoped `kotlin`, so it never asks the Java classifier this anyway.
     */
    fun isScopeFunctionInvocation(n: PsiElement): Boolean = false

    // --- Declarations ---

    fun parameters(fn: PsiElement): List<Param>

    fun annotations(decl: PsiElement): List<String>

    fun modifiers(decl: PsiElement): Modifiers

    // --- Resolved enrichments (name-based initially, resolved once resolution is on) ---

    /** RFC: methods called within [scope], resolved via the shared symbol space. */
    fun calledSymbols(scope: PsiElement): List<SymbolRef>

    /** CBO: distinct types referenced within [scope], resolved, cross-language. */
    fun referencedTypes(scope: PsiElement): List<TypeRef>

    /**
     * Raw (NON-deduplicated) simple names of every call + type reference in [scope], in source order.
     * Drives call-graph fan-in/fan-out multiplicity (the `*Calls` signals); [calledSymbols] /
     * [referencedTypes] dedup by key and so can't supply the per-reference counts these totals need.
     * The default reuses the deduped refs (loses multiplicity); the base classifiers override with the
     * raw walk.
     */
    fun outgoingRefNames(scope: PsiElement): List<String> = calledSymbols(scope).map { it.name } + referencedTypes(scope).map { it.name }

    /**
     * Reachability: the simple names of every value-level name reference in [scope] — property/field
     * reads, object/companion qualifiers, callable references. [calledSymbols]/[referencedTypes]
     * deliberately cover only call expressions and type positions, so a read like `Config.DEFAULT`
     * (no call, no type position) is invisible to them yet must keep `Config` alive in the unused
     * detector. Name-based by design — the detector's reachability errs toward over-connecting.
     * Defaults to empty: a classifier that doesn't opt in contributes no extra reachability edges.
     */
    fun referencedNames(scope: PsiElement): Set<String> = emptySet()

    /** DIT/NOC/abstractness: resolved supertypes of a type declaration. */
    fun supertypes(type: PsiElement): List<TypeRef>

    /** The set of instance fields/properties a function body reads or writes (LCOM4). */
    fun fieldAccesses(scope: PsiElement): Set<String>

    // --- Tokens ---

    /** Halstead operator/operand classification of every lexical token in [scope]. */
    fun tokens(scope: PsiElement): List<Token>

    // --- Generic traversal helpers (so calculators stay language-free) ---

    /** Direct children of [n] in source order. */
    fun children(n: PsiElement): List<PsiElement>

    /** Depth-first descendants of [n] (excluding [n] itself). */
    fun descendants(n: PsiElement): Sequence<PsiElement> =
        sequence {
            for (child in children(n)) {
                yield(child)
                yieldAll(descendants(child))
            }
        }

    /** Source text of [n], used for SLOC and snippet slicing. */
    fun text(n: PsiElement): String
}
