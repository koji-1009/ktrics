package calib

/** Coupling + cohesion shapes for the class-level (CK) calculators. */

class Dep1 {
    fun ping(): Int = 1
}

class Dep2 {
    fun pong(): Int = 2
}

/**
 * Three methods: first()+second() share the `counter` field (and first calls second), while third()
 * only touches `b` — so the cohesion graph splits into two components (LCOM4 = 2). The class references
 * Dep1 and Dep2 (CBO), and first() calls second() (RFC includes the call).
 */
class Coupled {
    private val a: Dep1 = Dep1()
    private val b: Dep2 = Dep2()
    private var counter: Int = 0

    fun first(): Int {
        counter += 1
        return second()
    }

    fun second(): Int = counter * 2

    fun third(): Int = b.pong()
}

/** Generic + nullable type references — for resolved-vs-name-based key-shape coverage. */
class Generic {
    val maybe: Dep1? = null
    val many: List<Dep1> = emptyList()
}

interface Greeter {
    fun greet(): String
}

open class Base {
    fun base(): Int = 0
}

/** Extends a class and implements an interface → two resolved supertypes. */
class Polite : Base(), Greeter {
    override fun greet(): String = "hi"
}
