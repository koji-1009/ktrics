package calib

/**
 * Operator-convention shapes: every member of [Vec] is reached ONLY through its operator form —
 * never a named call — pinning the convention-edge extraction (a[i]/a + b/in/invoke/destructuring/
 * for-in/by) that keeps user-defined operators out of the unused report.
 */
class Vec(val x: Int) {
    operator fun plus(other: Vec): Vec = Vec(x + other.x)

    operator fun get(i: Int): Int = x + i

    operator fun contains(v: Int): Boolean = v == x

    operator fun component1(): Int = x

    operator fun invoke(): Int = x

    operator fun unaryMinus(): Vec = Vec(0 - x)

    operator fun inc(): Vec = Vec(x + 1)
}

class OperatorUse {
    fun useThem(
        a: Vec,
        b: Vec,
    ): Int {
        val sum = a + b // plus
        val item = a[0] // get
        val has = 1 in a // contains
        val (first) = a // component1
        val called = a() // invoke
        return sum.x + item + (if (has) 1 else 0) + first + called
    }

    fun loopAndDelegate(): Int {
        var total = 0
        for (v in listOf(1, 2)) { // iterator/hasNext/next
            total += v
        }
        val lazyValue: Int by lazy { 5 } // getValue
        return total + lazyValue
    }

    fun useUnary(a: Vec): Int {
        var v = -a // unaryMinus (prefix form)
        v++ // inc (postfix form — compiles through the inc convention)
        val s: String? = "x"
        return v.x + s!!.length // `!!` is a postfix node with NO convention — the null lookup path
    }
}

fun main() {
    val use = OperatorUse()
    println(use.useThem(Vec(1), Vec(2)) + use.loopAndDelegate() + use.useUnary(Vec(3)))
}
