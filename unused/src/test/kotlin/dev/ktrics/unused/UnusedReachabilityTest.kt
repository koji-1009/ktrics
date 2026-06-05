package dev.ktrics.unused

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.TypeKind
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
    fun `a keep-alive annotation on a type covers its members`() {
        val c = FakeClassifier()
        // A @Serializable-style type whose public method is touched only reflectively (no in-project
        // caller): the annotation keep-alive must propagate to members — dartrics' propagation rule.
        val method = c.fn("toJson")
        val entity = c.type("Payload", methods = listOf(method), annotations = listOf("Serializable"))
        val plainMethod = c.fn("compute")
        val plain = c.type("Plain", methods = listOf(plainMethod))
        val config = UnusedConfig(keepAliveAnnotations = setOf("Serializable"))
        val report = UnusedDetector(listOf(unit(types = listOf(entity, plain))), { c }, config).detect()
        val names = report.unused.map { it.displayName }
        names shouldNotContain "Payload"
        names shouldNotContain "Payload.toJson()"
        // The un-annotated control: its member is still reported, so the propagation is annotation-scoped.
        names shouldContain "Plain.compute()"
    }

    @Test
    fun `keep-alive propagation reaches nested types and stops at name prefixes`() {
        val c = FakeClassifier()
        val nestedMethod = c.fn("write")
        val nested = c.type("Companion", pkg = "pkg.Payload", methods = listOf(nestedMethod))
        val entity = c.type("Payload", nested = listOf(nested), annotations = listOf("Serializable"))
        // `pkg.PayloadExtra` shares the kept key as a NAME prefix but is a different type — not covered.
        val lookalike = c.type("PayloadExtra")
        val config = UnusedConfig(keepAliveAnnotations = setOf("Serializable"))
        val report = UnusedDetector(listOf(unit(types = listOf(entity, lookalike))), { c }, config).detect()
        val names = report.unused.map { it.displayName }
        names shouldNotContain "Companion.write()"
        names shouldContain "PayloadExtra"
    }

    // --- supertype keep-alive (frameworks that instantiate by inheritance, e.g. Android) ---

    @Test
    fun `a configured supertype suffix keeps a framework-instantiated type, its members, and its callees`() {
        val c = FakeClassifier()
        // MainActivity is manifest-wired: nothing in source references it, but it must stay alive —
        // along with its members and the helper its body calls (it is a ROOT, not just unreported).
        val helper = c.fn("renderHelp")
        val onCreate = c.fn("setup", calls = listOf("renderHelp"))
        val activity = c.type("MainActivity", methods = listOf(onCreate), supertypes = listOf("AppCompatActivity"))
        val orphan = c.type("Orphan")
        val config = UnusedConfig(keepAliveSupertypes = setOf("Activity"))
        val report = UnusedDetector(listOf(unit(fns = listOf(helper), types = listOf(activity, orphan))), { c }, config).detect()
        val names = report.unused.map { it.displayName }
        names shouldNotContain "MainActivity"
        names shouldNotContain "MainActivity.setup()"
        names shouldNotContain "renderHelp()" // reached FROM the kept root
        names shouldContain "Orphan" // the control: no matching supertype, still reported
    }

    @Test
    fun `supertype keep-alive is transitive through project base classes`() {
        val c = FakeClassifier()
        // Main : Base, Base : AppCompatActivity — Main carries no framework suffix of its own, but
        // extends a kept project type, so the closure must keep it (and its members) too.
        val base = c.type("Base", supertypes = listOf("AppCompatActivity"))
        val show = c.fn("show")
        val main = c.type("Main", methods = listOf(show), supertypes = listOf("Base"))
        val config = UnusedConfig(keepAliveSupertypes = setOf("Activity"))
        val report = UnusedDetector(listOf(unit(types = listOf(base, main))), { c }, config).detect()
        val names = report.unused.map { it.displayName }
        names shouldNotContain "Base"
        names shouldNotContain "Main"
        names shouldNotContain "Main.show()"
    }

    @Test
    fun `supertype matching is a case-sensitive suffix - CamelCase bounds it`() {
        val c = FakeClassifier()
        // GridView ends with `View` (kept); Preview's lowercase tail does NOT match `View`.
        val widget = c.type("Chart", supertypes = listOf("GridView"))
        val preview = c.type("Thumb", supertypes = listOf("Preview"))
        val config = UnusedConfig(keepAliveSupertypes = setOf("View"))
        val report = UnusedDetector(listOf(unit(types = listOf(widget, preview))), { c }, config).detect()
        val names = report.unused.map { it.displayName }
        names shouldNotContain "Chart"
        names shouldContain "Thumb"
    }

    @Test
    fun `without configured supertypes an extending type is still reported`() {
        val c = FakeClassifier()
        val activity = c.type("MainActivity", supertypes = listOf("AppCompatActivity"))
        val report = UnusedDetector(listOf(unit(types = listOf(activity))), { c }).detect()
        report.unused.map { it.displayName } shouldContain "MainActivity"
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

    // --- Container reachability (each case below was a dogfooded false positive) ---

    @Test
    fun `a called member keeps its container type alive`() {
        val c = FakeClassifier()
        val dispatch = c.fn("dispatch")
        val cli = c.type("ClientCli", kind = TypeKind.OBJECT, methods = listOf(dispatch))
        val main = c.fn("main", calls = listOf("dispatch"))
        val report = UnusedDetector(listOf(unit(fns = listOf(main), types = listOf(cli))), { c }).detect()
        // Using a member implies using its container: the object is not reported.
        report.unused.map { it.displayName } shouldNotContain "ClientCli"
    }

    @Test
    fun `a resolved call's container key keeps the owner alive`() {
        val c = FakeClassifier()
        val server = c.type("Server")
        // A resolved constructor call: SymbolRef("<init>", container = "pkg.Server"). The bare name
        // `<init>` matches no declaration — the class is reached only through the container channel.
        val main =
            c.fn(
                "main",
                calls = listOf("<init>"),
                callContainers = mapOf("<init>" to "pkg.Server"),
                resolution = Resolution.RESOLVED,
            )
        val report = UnusedDetector(listOf(unit(fns = listOf(main), types = listOf(server))), { c }).detect()
        report.unused.map { it.displayName } shouldNotContain "Server"
    }

    @Test
    fun `a value-level name reference keeps an object alive`() {
        val c = FakeClassifier()
        val registry = c.type("Registry", kind = TypeKind.OBJECT)
        // `Registry.DEFAULT`: the qualifier is no call and no type position — only a name reference.
        val main = c.fn("main", names = setOf("Registry"))
        val report = UnusedDetector(listOf(unit(fns = listOf(main), types = listOf(registry))), { c }).detect()
        report.unused.map { it.displayName } shouldNotContain "Registry"
    }

    @Test
    fun `a companion constant read keeps the companion and its owner alive`() {
        val c = FakeClassifier()
        val max = c.prop("MAX")
        val companion = c.type("Companion", pkg = "pkg.Owner", kind = TypeKind.OBJECT, fields = listOf(max))
        val owner = c.type("Owner", nested = listOf(companion))
        // Reading `Owner.MAX` references no member CALL — the field node carries the reachability:
        // MAX → Companion (member edge) → Owner (member edge).
        val main = c.fn("main", names = setOf("MAX"))
        val report = UnusedDetector(listOf(unit(fns = listOf(main), types = listOf(owner))), { c }).detect()
        val names = report.unused.map { it.displayName }
        names shouldNotContain "Companion"
        names shouldNotContain "Owner"
    }

    @Test
    fun `type-level fields are reachability nodes but are never reported`() {
        val c = FakeClassifier()
        val orphanField = c.prop("orphanField")
        val holder = c.type("Holder", fields = listOf(orphanField))
        val report = UnusedDetector(listOf(unit(types = listOf(holder))), { c }).detect()
        // The unreferenced type is reported as before; its field never is (not part of the surface).
        report.unused.map { it.displayName } shouldContain "Holder"
        report.unused.map { it.displayName } shouldNotContain "Holder.orphanField"
        report.consideredCount shouldBe 1 // the type only — fields don't widen the considered surface
    }

    @Test
    fun `a reachable container does not make its unreferenced member reachable`() {
        val c = FakeClassifier()
        val orphan = c.fn("orphan")
        val helper = c.type("Helper", methods = listOf(orphan))
        val main = c.fn("main", names = setOf("Helper"))
        val report = UnusedDetector(listOf(unit(fns = listOf(main), types = listOf(helper))), { c }).detect()
        // The container edge is one-directional: members must be referenced on their own.
        report.unused.map { it.displayName } shouldContain "Helper.orphan()"
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
