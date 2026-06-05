package dev.ktrics.metric

import dev.ktrics.ir.Lang
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.TypeKind
import dev.ktrics.metric.clazz.CouplingBetweenObjects
import dev.ktrics.metric.clazz.DepthOfInheritanceTree
import dev.ktrics.metric.clazz.NumberOfChildren
import dev.ktrics.metric.clazz.NumberOfMethods
import dev.ktrics.testsupport.FakeClassifier
import dev.ktrics.testsupport.fnDecl
import dev.ktrics.testsupport.sourceUnit
import dev.ktrics.testsupport.type
import dev.ktrics.testsupport.typeDecl
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Hand-written behaviour of the metric model: the enum wire-name mappings, interface default methods,
 * and the class-calculator edge branches (no index / no references). Generated members (valueOf/entries,
 * data-class copy, serializers) are deliberately NOT tested — they exercise the compiler, not ktrics.
 */
class ModelSurfaceTest {
    @Test
    fun `enum wire names map to their documented strings`() {
        Polarity.LOWER_IS_BETTER.wireName shouldBe "LowerIsBetter"
        Polarity.HIGHER_IS_BETTER.wireName shouldBe "HigherIsBetter"
        Polarity.INFORMATIONAL.wireName shouldBe "Informational"
        AppliesTo.JAVA.wireName shouldBe "java"
        AppliesTo.KOTLIN.wireName shouldBe "kotlin"
        AppliesTo.BOTH.wireName shouldBe "both"
        Severity.WARNING.wireName shouldBe "warning"
        Severity.ERROR.wireName shouldBe "error"
    }

    @Test
    fun `appliesTo matches each language correctly`() {
        AppliesTo.BOTH.matches(Lang.JAVA) shouldBe true
        AppliesTo.BOTH.matches(Lang.KOTLIN) shouldBe true
        AppliesTo.JAVA.matches(Lang.JAVA) shouldBe true
        AppliesTo.JAVA.matches(Lang.KOTLIN) shouldBe false
        AppliesTo.KOTLIN.matches(Lang.KOTLIN) shouldBe true
        AppliesTo.KOTLIN.matches(Lang.JAVA) shouldBe false
    }

    @Test
    fun `the default metric settings and skip policy behave as no-ops`() {
        val def = NumberOfMethods().def
        MetricSettings.Default.isEnabled(def) shouldBe def.enabledByDefault
        MetricSettings.Default.thresholds(def, Lang.KOTLIN) shouldBe def.thresholdsFor(Lang.KOTLIN)
        val unit = sourceUnit()
        val type = typeDecl("T")
        SkipPolicy.None.skipFunction(def, fnDecl("f"), type, unit) shouldBe false
        SkipPolicy.None.skipType(def, type, unit) shouldBe false
        SkipPolicy.None.skipFile(def, unit) shouldBe false
        SkipPolicy.None.testDslDiscount(unit) shouldBe false
    }

    @Test
    fun `a type metric reports no resolution by default`() {
        // NumberOfMethods does not override resolution() → the interface default returns null.
        val c = FakeClassifier()
        NumberOfMethods().resolution(typeDecl("T"), MeasureContext(c, sourceUnit())).shouldBeNull()
    }

    @Test
    fun `inheritance lenses degrade gracefully without an index`() {
        val c = FakeClassifier()
        val ctx = MeasureContext(c, sourceUnit())
        // No ProjectIndex → DIT falls back to the declared supertype count; NOC has nothing to count.
        DepthOfInheritanceTree().measure(typeDecl("T"), ctx) shouldBe 0.0
        NumberOfChildren().measure(typeDecl("T"), ctx).shouldBeNull()
    }

    @Test
    fun `coupling of a type with no references is zero and vacuously resolved`() {
        val c = FakeClassifier()
        val solo = c.type("Solo", pkg = "p")
        CouplingBetweenObjects().measure(solo, MeasureContext(c, sourceUnit())) shouldBe 0.0
        CouplingBetweenObjects().resolution(solo, MeasureContext(c, sourceUnit())) shouldBe Resolution.RESOLVED
    }

    @Test
    fun `a record kind is data-like`() {
        typeDecl("R", kind = TypeKind.RECORD).isDataLike shouldBe true
    }
}
