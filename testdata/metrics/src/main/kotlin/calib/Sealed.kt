package calib

/** Sealed-dispatch shapes for the resolved-mode cyclomatic discount (doc/calibration.md). */

sealed class Shade {
    object Light : Shade()

    object Dark : Shade()
}

class SealedShapes {
    /** Exhaustive when over a SEALED subject: arms are compiler-enforced enumeration → cyclomatic 1 (resolved). */
    fun sealedDispatch(s: Shade): Int =
        when (s) {
            is Shade.Light -> 1
            is Shade.Dark -> 2
        }
}
