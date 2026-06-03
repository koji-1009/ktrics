package com.example.core;

/**
 * Spike fixture. Its method BODY must materialize in the standalone session — that is the
 * make-or-break check: body-level metrics for Java come from Java PSI, not from FIR.
 */
public class JavaGreeter {

    public String hello(String name) {
        // A real body with a decision point, so "did the body materialize?" is observable.
        if (name == null || name.isEmpty()) {
            return "hello, stranger";
        }
        return "hello, " + name;
    }
}
