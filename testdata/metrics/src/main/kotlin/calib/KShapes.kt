package calib

/**
 * Kotlin shapes with known metric values, mirrored by JShapes.java. Used to pin the function-level
 * calculators and to prove cross-language calibration (doc/calibration.md).
 */
class KShapes {

    /** No decision points → cyclomatic 1, nesting 0. */
    fun straight(): Int {
        val a = 1
        val b = 2
        return a + b
    }

    /** if + two `&&` → cyclomatic 1 + 1 + 2 = 4 (each `&&` is its own node in Kotlin). */
    fun polyadicAnd(a: Boolean, b: Boolean, c: Boolean): Int {
        if (a && b && c) return 1
        return 0
    }

    /** `when` with three non-else entries → cyclomatic 1 + 3 = 4. */
    fun whenFour(x: Int): String = when (x) {
        0 -> "zero"
        1 -> "one"
        2 -> "two"
        else -> "many"
    }

    /** Two nested ifs → cyclomatic 3, maximum-nesting-level 2. */
    fun nestedTwo(a: Boolean, b: Boolean): Int {
        if (a) {
            if (b) {
                return 2
            }
        }
        return 0
    }

    /** Four required params → number-of-parameters 4. */
    fun fourParams(a: Int, b: Int, c: Int, d: Int): Int = a + b + c + d

    /** One required + two defaulted → number-of-parameters 1 (defaults discounted on Kotlin). */
    fun twoDefaulted(a: Int, b: Int = 1, c: Int = 2): Int = a + b + c

    /** Three boolean params → boolean-trap 3. */
    fun threeBooleans(x: Boolean, y: Boolean, z: Boolean) {
        if (x || y || z) return
    }

    /** Two `!!` assertions → not-null-assertion-density 2. */
    fun bangBang(s: String?): Int = s!!.length + s!!.hashCode()

    /** A scope function nested in a scope function → scope-function-nesting 2. */
    fun nestedScopes(s: String): Int = s.let { outer -> outer.length.let { inner -> inner + 1 } }

    /** A for and a while loop → loop/branch nodes (npath multiplier 2 each). */
    fun loops(n: Int): Int {
        var s = 0
        for (i in 0 until n) {
            s += i
        }
        while (s > 100) {
            s -= 10
        }
        return s
    }

    /** Flat if/else-if/else-if/else chain → cognitive 4 (head 1 + two flat else-if + flat else), nesting 1. */
    fun elseIfChain(x: Int): String {
        if (x == 0) {
            return "zero"
        } else if (x == 1) {
            return "one"
        } else if (x == 2) {
            return "two"
        } else {
            return "many"
        }
    }

    /** Labeled continue in nested loops → cognitive: for(1) + inner for(2) + labeled jump(1) = 4. */
    fun labeledJump(n: Int): Int {
        outer@ for (i in 0 until n) {
            for (j in 0 until n) {
                continue@outer
            }
        }
        return n
    }

    /** A branch inside a HOF lambda: the lambda nests WITHOUT an increment → if costs 1+1 → cognitive 2. */
    fun lambdaNesting(items: List<Int>): Int {
        var s = 0
        items.forEach { v ->
            if (v > 0) {
                s += v
            }
        }
        return s
    }

    /** A scope-fn lambda nests once with no increment of its own → the inner if costs 1+1 → cognitive 2. */
    fun scopeFnCost(s: String?): Int = s?.let { if (it.isEmpty()) 0 else it.length } ?: 0

    /** Closures handed to a call: the test-DSL discount skips them entirely on test files. */
    fun dslRegistration() {
        register("a") {
            if (flag()) {
                emit()
            }
        }
        register("b") {
            if (flag()) {
                emit()
            }
        }
    }

    private fun register(
        name: String,
        body: () -> Unit,
    ) {
        if (name.isNotEmpty()) body()
    }

    private fun flag(): Boolean = true

    private fun emit() {}
}
