package dev.ktrics.engine

/** Engine build identity, read from the jar manifest with a constant fallback for dev/test runs. */
object Build {
    val VERSION: String = Build::class.java.`package`?.implementationVersion ?: "0.1.0-SNAPSHOT"
}
