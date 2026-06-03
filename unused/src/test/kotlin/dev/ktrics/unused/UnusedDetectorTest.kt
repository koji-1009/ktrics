package dev.ktrics.unused

import dev.ktrics.ir.Resolution
import dev.ktrics.ir.Visibility
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UnusedDetectorTest {
    @Test
    fun `reports an unreferenced public function but not a reached one or main`() {
        val c = FakeClassifier()
        val used = c.fn("usedHelper")
        val unused = c.fn("unusedHelper")
        val main = c.fn("main", calls = listOf("usedHelper"))
        val report = UnusedDetector(listOf(unit(fns = listOf(used, unused, main))), { c }).detect()

        report.unused.map { it.displayName } shouldContainExactly listOf("unusedHelper()")
    }

    @Test
    fun `a keep-alive annotation suppresses a symbol`() {
        val c = FakeClassifier()
        val exposed = c.fn("exposed", annotations = listOf("Generated"))
        val config = UnusedConfig(keepAliveAnnotations = setOf("Generated"))
        val report = UnusedDetector(listOf(unit(fns = listOf(exposed))), { c }, config).detect()

        report.unused.shouldBeEmpty()
    }

    @Test
    fun `private, test-tree and generated declarations are not reported`() {
        val c = FakeClassifier()
        val mainSrc =
            unit(
                path = "mod/src/main/kotlin/M.kt",
                fns = listOf(c.fn("publicOrphan"), c.fn("privOrphan", visibility = Visibility.PRIVATE)),
            )
        val testSrc = unit(path = "mod/src/test/kotlin/T.kt", fns = listOf(c.fn("testOrphan")))
        val generated = unit(path = "build/generated/G.kt", fns = listOf(c.fn("genOrphan")))
        val report = UnusedDetector(listOf(mainSrc, testSrc, generated), { c }).detect()

        report.unused.map { it.displayName } shouldContainExactly listOf("publicOrphan()")
    }

    @Test
    fun `Kotlin protected is not part of the reportable surface`() {
        val c = FakeClassifier()
        val prot = c.fn("protectedOrphan", visibility = Visibility.PROTECTED)
        val report = UnusedDetector(listOf(unit(fns = listOf(prot))), { c }).detect()
        report.unused.shouldBeEmpty()
    }

    @Test
    fun `a call reference reaches its target, an unreferenced sibling stays unused`() {
        val c = FakeClassifier()
        val used = c.fn("used")
        val orphan = c.fn("orphan")
        val helper = c.type("Helper", methods = listOf(used, orphan))
        val main = c.fn("main", calls = listOf("used")) // simple-name reachability (the deliberate bias)
        val report = UnusedDetector(listOf(unit(fns = listOf(main), types = listOf(helper))), { c }).detect()

        val names = report.unused.map { it.displayName }
        names shouldNotContain "Helper.used()" // reached
        names shouldContain "Helper.orphan()" // genuinely unreached
    }

    @Test
    fun `an unreferenced top-level property is reported, a referenced one is reachable`() {
        val c = FakeClassifier()
        val usedProp = c.prop("usedProp")
        val orphanProp = c.prop("orphanProp")
        val main = c.fn("main", types = listOf("usedProp")) // simple-name reach via a type ref
        val report = UnusedDetector(listOf(unit(fns = listOf(main), props = listOf(usedProp, orphanProp))), { c }).detect()

        val orphan = report.unused.single()
        orphan.displayName shouldBe "orphanProp"
        orphan.kind shouldBe "property"
        orphan.topLevel shouldBe true
    }

    @Test
    fun `a nested type is part of the collected surface`() {
        val c = FakeClassifier()
        val inner = c.type("Inner", pkg = "pkg.Outer")
        val outer = c.type("Outer", nested = listOf(inner))
        val report = UnusedDetector(listOf(unit(types = listOf(outer))), { c }).detect()

        // Both the outer and the (non-top-level) nested type are collected and, unreferenced, reported.
        report.unused.map { it.displayName } shouldContain "Inner"
        report.unused.single { it.displayName == "Inner" }.topLevel shouldBe false
    }

    @Test
    fun `a bare reference over-connects to every same-named declaration (the deliberate bias)`() {
        val c = FakeClassifier()
        val fooHelper = c.fn("helper")
        val barHelper = c.fn("helper")
        val foo = c.type("Foo", methods = listOf(fooHelper))
        val bar = c.type("Bar", methods = listOf(barHelper))
        val main = c.fn("main", calls = listOf("helper")) // a single bare, unqualified reference
        val report = UnusedDetector(listOf(unit(fns = listOf(main), types = listOf(foo, bar))), { c }).detect()

        // The documented over-connection: one bare `helper` keeps BOTH Foo.helper and Bar.helper alive
        // (erring toward never deleting live code). A precise resolver would have reported one as dead.
        val names = report.unused.map { it.displayName }
        names shouldNotContain "Foo.helper()"
        names shouldNotContain "Bar.helper()"
    }

    @Test
    fun `resolution is name-based when any traversed edge is name-based`() {
        val c = FakeClassifier()
        val a = c.fn("a", calls = listOf("b"), resolution = Resolution.RESOLVED)
        val b = c.fn("b", calls = listOf("a"), resolution = Resolution.NAME_BASED)
        UnusedDetector(listOf(unit(fns = listOf(a, b))), { c }).detect().resolution shouldBe Resolution.NAME_BASED
    }

    @Test
    fun `resolution is resolved when every edge resolves`() {
        val c = FakeClassifier()
        val a = c.fn("a", calls = listOf("b"), resolution = Resolution.RESOLVED)
        val b = c.fn("b", resolution = Resolution.RESOLVED)
        UnusedDetector(listOf(unit(fns = listOf(a, b))), { c }).detect().resolution shouldBe Resolution.RESOLVED
    }

    @Test
    fun `a name-based call edge is not masked by a resolved type edge`() {
        val c = FakeClassifier()
        // `a` has a RESOLVED type edge (to T) but a NAME_BASED call edge (to b). Both must be counted,
        // so the whole sweep degrades to NAME_BASED — a resolved type must not mask call edges.
        val a =
            c.fn(
                "a",
                calls = listOf("b"),
                types = listOf("T"),
                callResolution = Resolution.NAME_BASED,
                typeResolution = Resolution.RESOLVED,
            )
        val b = c.fn("b", resolution = Resolution.RESOLVED)
        UnusedDetector(listOf(unit(fns = listOf(a, b))), { c }).detect().resolution shouldBe Resolution.NAME_BASED
    }
}
