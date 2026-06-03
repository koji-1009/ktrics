package com.example.app

import com.example.core.CoreApi
import com.example.core.JavaGreeter

/**
 * The `app` module depends on `core`. References both a Kotlin type (CoreApi) and a Java type
 * (JavaGreeter) from the dependency module — proving cross-module resolution app→core, both for a
 * Kotlin→Kotlin and a Kotlin→Java edge.
 */
class App {
    fun run(): String {
        val core = CoreApi().ping()
        val greeting = JavaGreeter().hello("app")
        return "$core / $greeting"
    }
}
