package dev.ktrics.unused

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Visibility
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Reachability correctness: entry-point annotations, visibility surface, multi-hop BFS, type edges. */
class UnusedReachabilityTest {
    @Test
    fun `a Test-annotated function is a root and keeps its callees alive`() {
        val c = FakeClassifier()
        val helper = c.fn("helper")
        val testFn = c.fn("checks", calls = listOf("helper"), annotations = listOf("Test"))
        val report = UnusedDetector(listOf(unit(fns = listOf(helper, testFn))), { c }).detect()
        // @Test is an entry point → both the test and its callee are reachable.
        report.unused.map { it.displayName } shouldNotContain "helper()"
        report.unused.map { it.displayName } shouldNotContain "checks()"
    }

    @Test
    fun `Kotlin internal is part of the reportable surface`() {
        val c = FakeClassifier()
        val internalOrphan = c.fn("internalOrphan", visibility = Visibility.INTERNAL)
        val report = UnusedDetector(listOf(unit(fns = listOf(internalOrphan))), { c }).detect()
        report.unused.map { it.displayName } shouldContain "internalOrphan()"
    }

    @Test
    fun `Java protected is reported but package-private is not`() {
        val c = FakeClassifier()
        val prot = c.fn("protOrphan", visibility = Visibility.PROTECTED, lang = Lang.JAVA)
        val pkg = c.fn("pkgOrphan", visibility = Visibility.PACKAGE_PRIVATE, lang = Lang.JAVA)
        val report = UnusedDetector(listOf(unit(fns = listOf(prot, pkg), lang = Lang.JAVA)), { c }).detect()
        val names = report.unused.map { it.displayName }
        names shouldContain "protOrphan()"
        names shouldNotContain "pkgOrphan()"
    }

    @Test
    fun `reachability is transitive across multiple hops`() {
        val c = FakeClassifier()
        val cc = c.fn("c")
        val bb = c.fn("b", calls = listOf("c"))
        val aa = c.fn("a", calls = listOf("b"))
        val main = c.fn("main", calls = listOf("a"))
        val orphan = c.fn("orphan")
        val report = UnusedDetector(listOf(unit(fns = listOf(cc, bb, aa, main, orphan))), { c }).detect()
        // main → a → b → c are all reachable; only the orphan is reported.
        report.unused.map { it.displayName } shouldBe listOf("orphan()")
    }

    @Test
    fun `a referenced type is reachable and an unreferenced type is unused`() {
        val c = FakeClassifier()
        val used = c.type("UsedType")
        val orphan = c.type("OrphanType")
        val main = c.fn("main", types = listOf("UsedType"))
        val report = UnusedDetector(listOf(unit(fns = listOf(main), types = listOf(used, orphan))), { c }).detect()
        val names = report.unused.map { it.displayName }
        names shouldContain "OrphanType"
        names shouldNotContain "UsedType"
    }

    @Test
    fun `the report records root and considered counts and the top-level flag`() {
        val c = FakeClassifier()
        val main = c.fn("main")
        val orphan = c.fn("orphan")
        val report = UnusedDetector(listOf(unit(fns = listOf(main, orphan))), { c }).detect()
        report.rootCount shouldBe 1 // main
        report.consideredCount shouldBe 2 // both public top-level fns are reportable surface
        report.unused.single().topLevel shouldBe true
    }
}
