package dev.ktrics.unused

import dev.ktrics.ir.TypeKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CallGraphTest {
    @Test
    fun `fan-out counts edges, not distinct callees`() {
        val c = FakeClassifier()
        val caller = c.fn("caller", calls = listOf("target", "target", "target"))
        val target = c.fn("target")
        val g = CallGraph.build(listOf(unit(fns = listOf(caller, target))), { c })

        val callerSignal = g.signalOf("pkg.caller")!!
        callerSignal.fanOutCallees shouldBe 1
        callerSignal.fanOutCalls shouldBe 3
        val targetSignal = g.signalOf("pkg.target")!!
        targetSignal.fanInCallers shouldBe 1
        targetSignal.fanInCalls shouldBe 3
    }

    @Test
    fun `allSignals returns one signal per project-local declaration`() {
        val c = FakeClassifier()
        val g = CallGraph.build(listOf(unit(fns = listOf(c.fn("a"), c.fn("b")))), { c })
        g.allSignals().map { it.scopeName } shouldContainExactlyInAnyOrder listOf("pkg.a", "pkg.b")
    }

    @Test
    fun `references with no project-local target are excluded as edges`() {
        val c = FakeClassifier()
        val useStd = c.fn("useStd", calls = listOf("println"), types = listOf("List"))
        val g = CallGraph.build(listOf(unit(fns = listOf(useStd))), { c })

        val signal = g.signalOf("pkg.useStd")!!
        signal.fanOutCallees shouldBe 0
        signal.fanOutCalls shouldBe 0
    }

    @Test
    fun `a qualified off-project reference does not link to a same-named project declaration`() {
        val c = FakeClassifier()
        val projectRun = c.fn("run")
        val caller = c.fn("caller", calls = listOf("kotlin.run")) // qualified, off-project
        val g = CallGraph.build(listOf(unit(fns = listOf(projectRun, caller))), { c })
        // `kotlin.run` is qualified and not a project node → it must NOT fall back to simple-name matching
        // and link to `pkg.run`, or every project `.run` would be spuriously coupled.
        g.signalOf("pkg.run")!!.fanInCallers shouldBe 0
    }

    @Test
    fun `a self-recursive call is not counted as a fan-out edge`() {
        val c = FakeClassifier()
        val recursive = c.fn("recurse", calls = listOf("recurse"))
        val g = CallGraph.build(listOf(unit(fns = listOf(recursive))), { c })
        g.signalOf("pkg.recurse")!!.fanOutCallees shouldBe 0 // a node never couples to itself
    }

    @Test
    fun `inspect walks upstream callers with depth and edge weight`() {
        val c = FakeClassifier()
        val main = c.fn("main", calls = listOf("caller", "caller")) // invokes caller twice
        val caller = c.fn("caller", calls = listOf("anchor"))
        val anchor = c.fn("anchor")
        val g = CallGraph.build(listOf(unit(fns = listOf(main, caller, anchor))), { c })

        val match = g.inspect("anchor", 2, InspectionDirection.UP).matches.single()
        match.upstream.map { it.depth to it.signal.scopeName } shouldContainExactly
            listOf(1 to "pkg.caller", 2 to "pkg.main")
        match.upstream.single { it.depth == 2 }.incomingEdgeCount shouldBe 2
        match.downstream.shouldBeEmpty()
    }

    @Test
    fun `inspect downstream sorts by widest fan-out first`() {
        val c = FakeClassifier()
        val leaves = listOf("wa", "wb", "wc", "na").map { c.fn(it) }
        val wide = c.fn("wide", calls = listOf("wa", "wb", "wc"))
        val narrow = c.fn("narrow", calls = listOf("na"))
        val root = c.fn("root", calls = listOf("wide", "narrow"))
        val g = CallGraph.build(listOf(unit(fns = leaves + listOf(wide, narrow, root))), { c })

        g.inspect("root", 1, InspectionDirection.DOWN).matches.single()
            .downstream.map { it.signal.scopeName } shouldContainExactly listOf("pkg.wide", "pkg.narrow")
    }

    @Test
    fun `inspect upstream sorts same-depth callers by widest fan-in first`() {
        val c = FakeClassifier()
        val anchor = c.fn("anchor")
        // hi and lo both call anchor directly → both land at depth 1 (a tie). hi is more widely called than lo.
        val hi = c.fn("hi", calls = listOf("anchor"))
        val lo = c.fn("lo", calls = listOf("anchor"))
        val callersOfHi = listOf("h1", "h2").map { c.fn(it, calls = listOf("hi")) }
        val callerOfLo = c.fn("l1", calls = listOf("lo"))
        val g = CallGraph.build(listOf(unit(fns = listOf(anchor, hi, lo) + callersOfHi + callerOfLo)), { c })

        // Within the depth-1 tie, the fan-in tiebreaker orders hi (2 callers) ahead of lo (1 caller).
        g.inspect("anchor", 1, InspectionDirection.UP).matches.single()
            .upstream.map { it.signal.scopeName } shouldContainExactly listOf("pkg.hi", "pkg.lo")
    }

    @Test
    fun `inspect returns no matches for an unknown query`() {
        val c = FakeClassifier()
        val g = CallGraph.build(listOf(unit(fns = listOf(c.fn("foo")))), { c })
        g.inspect("doesNotExist", 2, InspectionDirection.BOTH).matches.shouldBeEmpty()
    }

    @Test
    fun `a top-level property is a graph node and its initializer reference is an edge`() {
        val c = FakeClassifier()
        val helper = c.fn("helper")
        val registry = c.prop("registry", types = listOf("helper")) // `val registry = helper()`-style init
        val g = CallGraph.build(listOf(unit(fns = listOf(helper), props = listOf(registry))), { c })

        val propSignal = g.signalOf("pkg.registry")!!
        propSignal.kind shouldBe "property"
        propSignal.fanOutCallees shouldBe 1
        g.signalOf("pkg.helper")!!.fanInCallers shouldBe 1
    }

    @Test
    fun `inspect matches through a Kotlin Companion segment`() {
        val c = FakeClassifier()
        val build = c.fn("build")
        val companion = c.type("Companion", pkg = "pkg.Foo", methods = listOf(build), kind = TypeKind.OBJECT)
        val foo = c.type("Foo", pkg = "pkg", nested = listOf(companion))
        val g = CallGraph.build(listOf(unit(types = listOf(foo))), { c })

        g.inspect("Foo.build", 1, InspectionDirection.BOTH).matches
            .map { it.anchor.scopeName } shouldContainExactly listOf("pkg.Foo.Companion.build")
    }
}
