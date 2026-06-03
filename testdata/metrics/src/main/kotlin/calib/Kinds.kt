package calib

/** Every Kotlin type kind + constructor/property shape, for exercising IR lowering. */

interface Speakable {
    fun speak(): String
}

enum class Color { RED, GREEN, BLUE }

object Singleton {
    fun ping(): Int = 1
}

annotation class Marker

sealed class Shape2 {
    class Circle(val r: Int) : Shape2()
    class Square(val s: Int) : Shape2()
}

/** Primary `val` param + body property + secondary constructor + method + nested type. */
class Rich(val id: Int, name: String) {
    val derived: String = name
    private var counter: Int = 0

    constructor(id: Int) : this(id, "default")

    fun method(): Int = counter

    class Nested {
        fun nested(): Int = 2
    }
}
