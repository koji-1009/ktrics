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

// Extends a base AND implements an interface that are declared nowhere (GhostBase/GhostMixin never
// compiled): supertypes() can't resolve either reference, so each degrades to a NAME_BASED TypeRef
// (the resolved classifier's unresolved-supertype branch). Isolated — nothing references it and it
// adds no resolved inheritance edge, so it perturbs no calibration metric. The @Override on toString
// also exercises modifiers()' annotation scan (the syntactic isOverride path).
class JGhostChild extends GhostBase implements GhostMixin {
    @Override
    public String toString() {
        return "ghost";
    }
}
