package dev.ktrics.metric.pkg

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.TypeDecl
import dev.ktrics.ir.TypeKind
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.PackageUnit
import dev.ktrics.metric.ProjectIndex
import dev.ktrics.testsupport.testSpan
import dev.ktrics.testsupport.typeDecl
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Martin 1994 package metrics over a fake index. Ce/Ca/I/A/D arithmetic. */
class PackageMetricTest {
    /** Canned coupling edges; everything else is a harmless default. */
    private class FakeIndex(
        private val efferent: Map<String, Set<String>> = emptyMap(),
        private val afferent: Map<String, Set<String>> = emptyMap(),
    ) : ProjectIndex {
        override val internalPackages: Set<String> = emptySet()

        override fun directSupertypeQNames(typeQName: String): List<String> = emptyList()

        override fun inheritanceResolution(typeQName: String): Resolution = Resolution.RESOLVED

        override fun childrenCountOf(typeQName: String): Int = 0

        override fun afferentPackagesOf(pkg: String): Set<String> = afferent[pkg].orEmpty()

        override fun efferentPackagesOf(pkg: String): Set<String> = efferent[pkg].orEmpty()
    }

    private fun pkg(
        name: String,
        types: List<TypeDecl>,
    ) = PackageUnit(name, types, importedQualifiedNames = emptyList(), span = testSpan(), lang = Lang.KOTLIN)

    private fun ctx(index: ProjectIndex) =
        MeasureContext(dev.ktrics.testsupport.FakeClassifier(), dev.ktrics.testsupport.sourceUnit(), index)

    private val types =
        listOf(
            typeDecl("IA", kind = TypeKind.INTERFACE),
            typeDecl("IB", kind = TypeKind.INTERFACE),
            typeDecl("CA", kind = TypeKind.CLASS),
            typeDecl("CB", kind = TypeKind.CLASS),
        )

    @Test
    fun `efferent and afferent coupling count distinct package edges`() {
        val index =
            FakeIndex(
                efferent = mapOf("pkg.a" to setOf("pkg.b", "pkg.c")),
                afferent = mapOf("pkg.a" to setOf("pkg.d")),
            )
        val unit = pkg("pkg.a", types)
        EfferentCoupling().measure(unit, ctx(index)) shouldBeExactly 2.0
        AfferentCoupling().measure(unit, ctx(index)) shouldBeExactly 1.0
    }

    @Test
    fun `a package metric reports no resolution by default`() {
        // None of the Martin lenses override resolution() → the PackageMetric interface default returns null.
        EfferentCoupling().resolution(pkg("pkg.a", types), ctx(FakeIndex())).shouldBeNull()
    }

    @Test
    fun `instability is Ce over Ca plus Ce`() {
        val index =
            FakeIndex(
                efferent = mapOf("pkg.a" to setOf("pkg.b", "pkg.c")),
                afferent = mapOf("pkg.a" to setOf("pkg.d")),
            )
        // I = 2 / (1 + 2) = 0.667.
        Instability().measure(pkg("pkg.a", types), ctx(index)) shouldBe (0.6667 plusOrMinus 0.001)
    }

    @Test
    fun `a package with no edges is maximally stable`() {
        // Ce = Ca = 0 → I defaults to 0 (avoids divide-by-zero).
        Instability().measure(pkg("leaf", types), ctx(FakeIndex())) shouldBeExactly 0.0
    }

    @Test
    fun `abstractness is the fraction of abstract and interface types`() {
        // 2 interfaces of 4 types → 0.5.
        Abstractness().measure(pkg("pkg.a", types), ctx(FakeIndex())) shouldBeExactly 0.5
    }

    @Test
    fun `an empty package has zero abstractness`() {
        Abstractness().measure(pkg("empty", emptyList()), ctx(FakeIndex())) shouldBeExactly 0.0
    }

    @Test
    fun `an annotation type counts toward abstractness alongside interfaces`() {
        // Abstractness is not interface-only: an annotation is abstract too (1 of 2 → 0.5).
        val mixed = listOf(typeDecl("Ann", kind = TypeKind.ANNOTATION), typeDecl("Concrete", kind = TypeKind.CLASS))
        Abstractness().measure(pkg("pkg.x", mixed), ctx(FakeIndex())) shouldBeExactly 0.5
    }

    @Test
    fun `distance from the main sequence is the deviation from A plus I equals one`() {
        val index =
            FakeIndex(
                efferent = mapOf("pkg.a" to setOf("pkg.b", "pkg.c")),
                afferent = mapOf("pkg.a" to setOf("pkg.d")),
            )
        // D = |A + I − 1| = |0.5 + 0.667 − 1| = 0.167.
        DistanceFromMainSequence().measure(pkg("pkg.a", types), ctx(index)) shouldBe (0.1667 plusOrMinus 0.001)
    }

    @Test
    fun `a leaf utility package sits in the zone of pain or uselessness at distance one`() {
        // Ce = Ca = 0 → I = 0; all-concrete → A = 0 → D = |0 + 0 − 1| = 1 (why D is informational).
        val concrete = listOf(typeDecl("C1"), typeDecl("C2"))
        DistanceFromMainSequence().measure(pkg("leaf", concrete), ctx(FakeIndex())) shouldBeExactly 1.0
    }
}
