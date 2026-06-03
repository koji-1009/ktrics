package dev.ktrics.client

import kotlin.system.exitProcess

/**
 * The native-image client entry. Thin by design: all logic lives in [ClientCli] (which is
 * unit-tested); this shell only relays argv and turns the result into a process exit code. The single
 * `exitProcess` call is the irreducible JVM boundary, so this file is excluded from coverage.
 */
fun main(rawArgs: Array<String>): Unit = exitProcess(ClientCli.dispatch(rawArgs.toList()))
