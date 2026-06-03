package com.example.core

/** Kotlin → Java reference within one module. Proves Kotlin resolves a Java symbol. */
class KotlinUsesJava {
    fun greet(name: String): String = JavaGreeter().hello(name)
}
