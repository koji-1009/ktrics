package calib;

/** Java coupling + inheritance shapes for the Java classifier. */

interface JGreeter {
    String greet();
}

class JBase {
    int base() {
        return 0;
    }
}

class JDep {
    int use() {
        return 1;
    }
}

/** Extends a class and implements an interface; calls a collaborator and a sibling method. */
class JCoupled extends JBase implements JGreeter {
    private final JDep dep = new JDep();

    public String greet() {
        return "hi";
    }

    int first() {
        return dep.use() + second();
    }

    int second() {
        return 2;
    }
}
