package h

/** A small, resolvable inheritance + cohesion fixture for the class-level (CK) calculators. */

interface Speaker {
    fun speak(): String
}

abstract class Animal : Speaker

open class Pet : Animal() {
    override fun speak(): String = "..."
}

class Dog : Pet() {
    override fun speak(): String = "woof"
}

/** One cohesive component: both methods touch the same field. LCOM4 = 1. */
class Cohesive {
    private val state = 0
    fun read(): Int = state
    fun readAgain(): Int = state + 1
}

/** Two disjoint components: {useA, useAgainA} and {useB, useAgainB}. LCOM4 = 2. */
class TwoClusters {
    private val a = 1
    private val b = 2
    fun useA(): Int = a + 1
    fun useAgainA(): Int = a + 10
    fun useB(): Int = b + 1
    fun useAgainB(): Int = b + 10
}

// A diamond: Diamond reaches Top via two distinct paths (Left, Right). DIT must take the LONGEST
// path (2), which only holds if the cycle guard is per-ancestor-path, not a single shared `seen` set.
interface Top
interface Left : Top
interface Right : Top
class Diamond : Left, Right
