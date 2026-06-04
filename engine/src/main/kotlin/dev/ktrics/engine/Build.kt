package dev.ktrics.engine

/**
 * Engine build identity, read from THIS jar's manifest (`Implementation-Version`, stamped by the
 * root build for every module). The fallback only appears in classes-dir runs (IDE, gradle test)
 * and is deliberately unreleasable so a dev build can never masquerade as a release.
 */
object Build {
    val VERSION: String = Build::class.java.`package`?.implementationVersion ?: "0.0.0-dev"
}
