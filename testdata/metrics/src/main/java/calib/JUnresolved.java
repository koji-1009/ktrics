package calib;

// Fixture for the RESOLVED Java classifier's degrade-to-name-based fallback: a method
// call OR a type reference that cannot resolve must fall back to the syntactic name. The unresolved
// symbols (ghostMethod, GhostType) are intentional and never compiled — the session only reads PSI.
@SuppressWarnings("all")
public class JUnresolved {
    // Calls a resolvable sibling AND an unresolvable method in the same scope.
    int mixedCalls() {
        ghostMethod();   // unresolved → name-based SymbolRef / outgoing ref
        return resolved();
    }

    int resolved() {
        return 1;
    }

    // An unresolvable TYPE inside a body scope: GhostType is declared nowhere, so the resolved
    // classifier degrades the type reference to a name-based ref (nameRef / presentableText fallback).
    int useGhost() {
        GhostType g = null;
        return resolved();
    }

    // A call into an anonymous class's OWN method: the resolved method's containing class has no
    // qualified name, exercising outgoingRefNames' owner-null branch (`else -> method.name`).
    Runnable anon() {
        return new Runnable() {
            public void run() {
                helper();
            }

            void helper() {}
        };
    }
}
