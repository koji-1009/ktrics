package sample

/** A deliberately complex declaration so the analyze pipeline produces stable, known violations. */
class Tangled {

    /** Cyclomatic 11 (≥ 10 warning): three guards, a loop+branch, a while+branch, a four-way when. */
    fun tangled(x: Int, a: Boolean, b: Boolean, c: Boolean): Int {
        var r = 0
        if (a) r += 1
        if (b) r += 1
        if (c) r += 1
        for (i in 0 until x) {
            if (i % 2 == 0) r += i else r -= i
        }
        while (r > 100) {
            r -= 10
            if (r == 50) break
        }
        when (x) {
            0 -> r += 1
            1 -> r += 2
            2 -> r += 3
            else -> r += 4
        }
        return r
    }

    /** A simple, clean method — must NOT produce a violation. */
    fun clean(value: Int): Int = value + 1
}
