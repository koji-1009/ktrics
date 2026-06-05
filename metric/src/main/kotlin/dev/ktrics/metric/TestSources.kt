package dev.ktrics.metric

/**
 * The one definition of "this path is a conventional test file", shared by the test-aware skip
 * policy (config module) and the engine's test-DSL discount wiring — so the two test-aware
 * behaviours can never disagree on what a test file is.
 */
object TestSources {
    /**
     * True when [path] sits under a conventional test source tree AND its basename carries a
     * conventional test-class suffix. Requiring both keeps test fixtures/helpers under
     * production-grade thresholds (mirroring the sibling tool's `_test.dart`-under-`test/` rule).
     */
    fun isTestFile(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        val inTestDir = "/test/" in normalized || "/androidTest/" in normalized || normalized.startsWith("test/")
        val name = normalized.substringAfterLast('/').substringBeforeLast('.')
        return inTestDir && (name.endsWith("Test") || name.endsWith("Tests"))
    }
}
