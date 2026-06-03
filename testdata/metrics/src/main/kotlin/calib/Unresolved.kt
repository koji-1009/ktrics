package calib

// Fixture for the RESOLVED Kotlin classifier's degrade-to-name-based fallback: it mixes
// references that resolve cleanly with ones that cannot, so a single scope drives both the resolved and
// the name-based branches of calledSymbols/referencedTypes/outgoingRefNames, plus callableKey's
// top-level (no owner class) path. The unresolved symbols are intentional — do not "fix" them.

/** A top-level function: a resolved call to it has a null owner class, exercising callableKey's pkg path. */
fun topLevelHelper(): Int = 42

@Suppress("UNRESOLVED_REFERENCE", "UNUSED_VARIABLE", "UNUSED_PARAMETER")
class Unresolved {
    /** Calls a resolvable top-level function AND an unresolvable one in the same scope. */
    fun mixedCalls(): Int {
        val ghost = ghostFunction() // unresolved → name-based SymbolRef / outgoing ref
        return topLevelHelper() // resolved top-level → callableKey's package-qualified path
    }

    /** References a type that does not exist → the type edge degrades to name-based. */
    fun unresolvedType() {
        val ghost: GhostType = TODO()
    }
}

// A type parameter that SHADOWS a primitive name: every reference to `Int` inside resolves to the type
// parameter, NOT kotlin.Int, so it has no expanded class symbol (classId == null). Because its simple
// name is still a primitive name, the resolved classifier must DROP it (not emit a spurious coupling) —
// the unresolved-AND-primitive-named else branch of referencedTypes.
@Suppress("UNUSED_PARAMETER")
class ShadowedPrimitive<Int> {
    fun identity(x: Int): Int = x
}

// Two distinct types with a same-named method `run`. Calling both from one scope is the canonical proof
// of resolution: the resolved classifier keys each call by its OWNER (HomonymA.run vs HomonymB.run),
// where the syntactic name-based classifier collapses both to the bare `run` — the disambiguation that
// is the whole reason resolution exists.
class HomonymA {
    fun run(): Int = 1
}

class HomonymB {
    fun run(): Int = 2
}

class HomonymCaller {
    fun callsBoth(): Int = HomonymA().run() + HomonymB().run()
}

// Calls a Java method (JDep lives in JCoupling.java, same `calib` package). The resolved Kotlin
// classifier must key this edge by the JAVA owner's qualified name — the cross-language resolution
// that is the headline of the feature.
class CallsJava {
    fun useJava(): Int = JDep().use()
}
