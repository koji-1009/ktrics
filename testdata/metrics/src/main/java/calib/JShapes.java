package calib;

/**
 * Java mirror of KShapes — identical control-flow shapes, so the language-neutral calculators must
 * produce identical values. The point of cross-language calibration (doc/calibration.md):
 * Java groups `a && b && c` into one polyadic node whose decision weight is the operator count (2),
 * matching Kotlin's two separate `&&` nodes.
 */
class JShapes {

    int straight() {
        int a = 1;
        int b = 2;
        return a + b;
    }

    int polyadicAnd(boolean a, boolean b, boolean c) {
        if (a && b && c) {
            return 1;
        }
        return 0;
    }

    String whenFour(int x) {
        switch (x) {
            case 0:
                return "zero";
            case 1:
                return "one";
            case 2:
                return "two";
            default:
                return "many";
        }
    }

    int nestedTwo(boolean a, boolean b) {
        if (a) {
            if (b) {
                return 2;
            }
        }
        return 0;
    }

    int fourParams(int a, int b, int c, int d) {
        return a + b + c + d;
    }

    void threeBooleans(boolean x, boolean y, boolean z) {
        if (x || y || z) {
            return;
        }
    }

    int loops(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += i;
        }
        while (s > 100) {
            s -= 10;
        }
        return s;
    }
}
