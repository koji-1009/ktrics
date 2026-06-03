package dev.ktrics.coverage

/** Coverage counters for one method. Branch is preferred over line for `complexityJustified`. */
data class MethodCoverage(
    val branchCovered: Int,
    val branchMissed: Int,
    val lineCovered: Int,
    val lineMissed: Int,
) {
    private val branchTotal get() = branchCovered + branchMissed
    private val lineTotal get() = lineCovered + lineMissed

    /** Branch coverage ratio, or null when the method has no branches (JaCoCo emits no BRANCH counter). */
    val branchRatio: Double? get() = if (branchTotal > 0) branchCovered.toDouble() / branchTotal else null
    val lineRatio: Double? get() = if (lineTotal > 0) lineCovered.toDouble() / lineTotal else null

    /** Branch coverage when available, else the line fallback (branch preferred). */
    val effectiveRatio: Double? get() = branchRatio ?: lineRatio

    /** Sums counters — used to aggregate overloads that share an (unsignatured) scope key. */
    operator fun plus(other: MethodCoverage): MethodCoverage =
        MethodCoverage(
            branchCovered + other.branchCovered,
            branchMissed + other.branchMissed,
            lineCovered + other.lineCovered,
            lineMissed + other.lineMissed,
        )
}

/**
 * Parsed coverage, keyed by fully-qualified `Owner.method`. `complexityJustified` is true
 * when branch coverage ≥ 0.8 (from JaCoCo branch counters, preferred over the line fallback), so a
 * complex-but-well-tested method can be surfaced as justified rather than just flagged.
 */
class CoverageData(private val byMethod: Map<String, MethodCoverage>) {
    fun forScope(scope: String): MethodCoverage? {
        val key = scope.substringBeforeLast('(') // tolerate signature suffixes
        // Exact match stays authoritative: a real class literally named `FooKt` resolves here untouched.
        byMethod[scope]?.let { return it }
        byMethod[key]?.let { return it }
        // Only when exact fails, relax for Kotlin top-level functions: the IR scope is `pkg.method`, but
        // JaCoCo records them under the synthetic file-class `pkg.FileKt`, so its key is `pkg.<X>Kt.method`.
        // Match a key that, after dropping a single `*Kt` class segment, equals the scope (same pkg+method).
        val pkg = key.substringBeforeLast('.', missingDelimiterValue = "")
        val method = key.substringAfterLast('.')
        if (pkg.isEmpty()) return null
        return byMethod.entries.firstOrNull { (k, _) ->
            val kPkg = k.substringBeforeLast('.', missingDelimiterValue = "")
            val kMethod = k.substringAfterLast('.')
            val fileClass = kPkg.substringAfterLast('.')
            kMethod == method && fileClass.endsWith("Kt") && kPkg.substringBeforeLast('.', "") == pkg
        }?.value
    }

    fun complexityJustified(
        scope: String,
        threshold: Double = JUSTIFIED_THRESHOLD,
    ): Boolean {
        val ratio = forScope(scope)?.effectiveRatio ?: return false
        return ratio >= threshold
    }

    fun isEmpty(): Boolean = byMethod.isEmpty()

    companion object {
        const val JUSTIFIED_THRESHOLD = 0.8
        val EMPTY = CoverageData(emptyMap())
    }
}
