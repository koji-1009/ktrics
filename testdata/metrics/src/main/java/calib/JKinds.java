package calib;

/** Every Java type kind + field/constructor shape, for exercising IR lowering. */

interface JSpeakable {
    String speak();
}

enum JColor {
    RED,
    GREEN,
    BLUE
}

@interface JMarker {
}

record JPoint(int x, int y) {
}

class JRich {
    public final int id;
    private int counter;

    JRich(int id) {
        this.id = id;
        this.counter = 0;
    }

    int method() {
        return counter;
    }

    static class JNested {
        int n() {
            return 2;
        }
    }
}
