package com.example.core;

/** Java → Kotlin reference within one module. Proves Java resolves a Kotlin symbol. */
public class JavaUsesKotlin {

    public String pong() {
        return new CoreApi().ping();
    }
}
