package dev.ktrics.testsupport

import java.io.File

/**
 * Golden-file assertions for reporter/output snapshots. Compares actual output against a checked-in
 * golden file and, on mismatch, fails with a line-level diff so the change is obvious. Set
 * `UPDATE_GOLDEN=1` (env) or `-Dktrics.updateGolden=true` to (re)write goldens instead of asserting
 * — the deliberate, reviewable way to accept an intended output change.
 */
object GoldenAssert {
    private val updateMode: Boolean =
        System.getenv("UPDATE_GOLDEN") == "1" || System.getProperty("ktrics.updateGolden") == "true"

    /** Locates the golden directory under a module's test resources, creating it on demand in update mode. */
    fun goldenDir(relative: String = "src/test/resources/golden"): File = File(System.getProperty("user.dir"), relative)

    /**
     * Asserts [actual] equals the contents of [golden]. In update mode (or when the golden is missing)
     * the golden is written and the assertion passes with a notice, so a first run bootstraps it.
     */
    fun assertMatches(
        golden: File,
        actual: String,
    ) {
        val normalizedActual = normalize(actual)
        if (updateMode || !golden.exists()) {
            golden.parentFile?.mkdirs()
            golden.writeText(normalizedActual)
            if (!updateMode) {
                // First-run bootstrap: a green-but-loud pass so the missing golden is noticed and committed.
                System.err.println("GoldenAssert: wrote new golden ${golden.path} (commit it).")
            }
            return
        }
        val expected = normalize(golden.readText())
        if (expected != normalizedActual) {
            throw AssertionError(buildDiff(golden, expected, normalizedActual))
        }
    }

    /** Convenience overload: resolve [name] under [goldenDir] then assert. */
    fun assertMatches(
        name: String,
        actual: String,
    ) = assertMatches(File(goldenDir(), name), actual)

    private fun normalize(text: String): String = text.replace("\r\n", "\n").trimEnd('\n')

    private fun buildDiff(
        golden: File,
        expected: String,
        actual: String,
    ): String {
        val exp = expected.lines()
        val act = actual.lines()
        val max = maxOf(exp.size, act.size)
        val diff = StringBuilder()
        diff.appendLine("Golden mismatch: ${golden.path}")
        diff.appendLine("(set UPDATE_GOLDEN=1 to accept the new output)")
        for (i in 0 until max) {
            val e = exp.getOrNull(i)
            val a = act.getOrNull(i)
            if (e != a) {
                if (e != null) diff.appendLine("-[${i + 1}] $e")
                if (a != null) diff.appendLine("+[${i + 1}] $a")
            }
        }
        return diff.toString()
    }
}
