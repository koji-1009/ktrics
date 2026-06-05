package dev.ktrics.metric.function

import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
import dev.ktrics.langapi.NodeClassifier
import dev.ktrics.metric.FunctionMetric
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.clazz.TopLevelDeclarationsPerFile
import dev.ktrics.metric.clazz.TypesPerFile
import dev.ktrics.testsession.SessionFixture
import dev.ktrics.testsession.function
import dev.ktrics.testsession.repoRoot
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Function-level calculators over a real session, pinned to the values in doc/calibration.md, with the
 * Java/Kotlin mirror proving cross-language calibration. KShapes.kt and JShapes.java are
 * the same control-flow shapes, so the language-neutral lenses must agree.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FunctionMetricCalibrationTest {
    private lateinit var fixture: SessionFixture
    private lateinit var kotlin: SourceUnit
    private lateinit var java: SourceUnit
    private lateinit var kinds: SourceUnit

    @BeforeAll
    fun setUp() {
        val graph = SessionFixture.singleModule(srcRoots = listOf("src/main/kotlin", "src/main/java"))
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/metrics"))
        kotlin = fixture.kotlinUnit("KShapes.kt")
        java = fixture.javaUnit("JShapes.java")
        kinds = fixture.kotlinUnit("Kinds.kt") // carries the bodyless interface method `Speakable.speak`
    }

    @AfterAll
    fun tearDown() = fixture.close()

    private fun measure(
        metric: FunctionMetric,
        unit: SourceUnit,
        fnName: String,
    ): Double? {
        val classifier: NodeClassifier = fixture.classifier(unit.lang)
        val fn: FunctionDecl = unit.function(fnName)
        return metric.measure(fn, MeasureContext(classifier, unit))
    }

    private fun cc(
        unit: SourceUnit,
        fn: String,
    ) = measure(CyclomaticComplexity(), unit, fn)

    private fun npath(
        unit: SourceUnit,
        fn: String,
    ) = measure(NpathComplexity(), unit, fn)

    // --- Cyclomatic complexity: identical across the two languages ---

    @Test
    fun `straight-line code is cyclomatic 1 in both languages`() {
        cc(kotlin, "straight") shouldBe 1.0
        cc(java, "straight") shouldBe 1.0
    }

    @Test
    fun `a polyadic AND chain is cyclomatic 4 in both languages`() {
        // Calibration: Java's one polyadic `a && b && c` node weighs 2 (operator count); Kotlin's two
        // `&&` nodes weigh 1 each. With the if (+1) and the base (+1) both total 4 — the calibration goal.
        cc(kotlin, "polyadicAnd") shouldBe 4.0
        cc(java, "polyadicAnd") shouldBe 4.0
    }

    @Test
    fun `a four-way when-or-switch is cyclomatic 4 in both languages`() {
        cc(kotlin, "whenFour") shouldBe 4.0
        cc(java, "whenFour") shouldBe 4.0
    }

    @Test
    fun `two nested ifs are cyclomatic 3 in both languages`() {
        cc(kotlin, "nestedTwo") shouldBe 3.0
        cc(java, "nestedTwo") shouldBe 3.0
    }

    // --- Npath: multiplicative over branch factors (the part cyclomatic understates) ---

    @Test
    fun `npath multiplies a 2x loop by a 2x loop to 4 in both languages`() {
        // `loops` has a for (×2) and a while (×2); npath is their product, not their sum.
        npath(kotlin, "loops") shouldBe 4.0
        npath(java, "loops") shouldBe 4.0
    }

    @Test
    fun `npath of a four-way when-or-switch is its branch count in both languages`() {
        // The multi-way node multiplies by its branch count (0/1/2/else → 4), unlike the loop ×2. This
        // pins the fix for the top-level-branch bug: NpathComplexity must count the root node, not just
        // its descendants, or an expression-body when/switch would be missed and report npath 1.
        npath(kotlin, "whenFour") shouldBe 4.0
        npath(java, "whenFour") shouldBe 4.0
    }

    @Test
    fun `size lenses are N-A for a bodyless interface method`() {
        // An interface method has no body, so SLOC and method-length are undefined (null), not 0 — this
        // exercises the bodyless null branch the other (block-body) cases never reach.
        measure(SourceLinesOfCode(), kinds, "speak").shouldBeNull()
        measure(MethodLength(), kinds, "speak").shouldBeNull()
    }

    // --- Cognitive complexity: nesting is penalised harder than cyclomatic ---

    @Test
    fun `cognitive complexity adds a nesting penalty to the deeper if`() {
        // Outer if at depth 0 (+1), inner if at depth 1 (+1+1=2) → 3, in both languages.
        measure(CognitiveComplexity(), kotlin, "nestedTwo") shouldBe 3.0
        measure(CognitiveComplexity(), java, "nestedTwo") shouldBe 3.0
    }

    @Test
    fun `an else-if chain charges flat per branch, not by nesting structure`() {
        // SonarSource per-branch rule: head if (+1) + two `else if` (+1 each, flat) + plain else (+1)
        // = 4 — NOT the 1+2+3 the raw child-nesting walk would produce. Identical in both languages.
        measure(CognitiveComplexity(), kotlin, "elseIfChain") shouldBe 4.0
        measure(CognitiveComplexity(), java, "elseIfChain") shouldBe 4.0
    }

    @Test
    fun `a labeled jump charges one flat increment`() {
        // outer for (+1) + inner for (+1+1) + labeled continue (+1, flat) = 4, in both languages.
        measure(CognitiveComplexity(), kotlin, "labeledJump") shouldBe 4.0
        measure(CognitiveComplexity(), java, "labeledJump") shouldBe 4.0
    }

    @Test
    fun `a lambda raises nesting without an increment of its own`() {
        // The forEach closure nests (no B1); the if inside costs 1+1 → 2, in both languages.
        measure(CognitiveComplexity(), kotlin, "lambdaNesting") shouldBe 2.0
        measure(CognitiveComplexity(), java, "lambdaNesting") shouldBe 2.0
    }

    @Test
    fun `a scope-function lambda nests once and charges nothing itself`() {
        // `s?.let { if … else … }`: the let lambda is nesting-only, the if costs 1+1, its else +1
        // flat (the spec charges every `else` keyword), elvis adds 0 → 3.
        measure(CognitiveComplexity(), kotlin, "scopeFnCost") shouldBe 3.0
    }

    @Test
    fun `the test-DSL discount skips argument closures entirely on test files`() {
        // Two registration closures each holding an if: production scoring gives 2+2 = 4; on a test
        // file (isTestFile) the closures are data handed to the DSL → 0. The named helpers keep
        // their own scores either way.
        val fn = kotlin.function("dslRegistration")
        val classifier = fixture.classifier(kotlin.lang)
        CognitiveComplexity().measure(fn, MeasureContext(classifier, kotlin)) shouldBe 4.0
        CognitiveComplexity().measure(fn, MeasureContext(classifier, kotlin, isTestFile = true)) shouldBe 0.0
    }

    // --- Maximum nesting level ---

    @Test
    fun `maximum nesting level counts two for two nested ifs in both languages`() {
        measure(MaximumNestingLevel(), kotlin, "nestedTwo") shouldBe 2.0
        measure(MaximumNestingLevel(), java, "nestedTwo") shouldBe 2.0
    }

    @Test
    fun `an else-if chain is one nesting level and lambdas do not deepen it`() {
        // The flat chain never deepens (1). Lambdas count for cognitive's B2 but NOT here: this lens
        // is control-structure depth, and builder-DSL lambdas would otherwise fire it on flat code.
        measure(MaximumNestingLevel(), kotlin, "elseIfChain") shouldBe 1.0
        measure(MaximumNestingLevel(), java, "elseIfChain") shouldBe 1.0
        measure(MaximumNestingLevel(), kotlin, "lambdaNesting") shouldBe 1.0
        measure(MaximumNestingLevel(), java, "lambdaNesting") shouldBe 1.0
    }

    // --- Number of parameters: Kotlin discounts defaults ---

    @Test
    fun `number-of-parameters counts every required parameter`() {
        measure(NumberOfParameters(), kotlin, "fourParams") shouldBe 4.0
        measure(NumberOfParameters(), java, "fourParams") shouldBe 4.0
    }

    @Test
    fun `kotlin defaulted parameters are discounted`() {
        // a is required; b and c have defaults → counted as 1.
        measure(NumberOfParameters(), kotlin, "twoDefaulted") shouldBe 1.0
    }

    // --- Boolean trap ---

    @Test
    fun `boolean-trap counts every boolean parameter in both languages`() {
        measure(BooleanTrap(), kotlin, "threeBooleans") shouldBe 3.0
        measure(BooleanTrap(), java, "threeBooleans") shouldBe 3.0
    }

    // --- npath is measure-only (no default threshold) ---

    @Test
    fun `npath has no default threshold (measure-only)`() {
        NpathComplexity().def.defaults.warning shouldBe null
        NpathComplexity().def.defaults.error shouldBe null
        // It still produces a value: nestedTwo has two binary branches → 2 × 2 = 4.
        measure(NpathComplexity(), java, "nestedTwo") shouldBe 4.0
    }

    // --- Kotlin-only idiom lenses ---

    @Test
    fun `not-null-assertion-density counts each double-bang`() {
        measure(NotNullAssertionDensity(), kotlin, "bangBang") shouldBe 2.0
    }

    @Test
    fun `scope-function-nesting measures nested let depth`() {
        measure(ScopeFunctionNesting(), kotlin, "nestedScopes") shouldBe 2.0
    }

    @Test
    fun `the kotlin idiom lenses are scoped to kotlin`() {
        NotNullAssertionDensity().def.appliesTo.matches(Lang.JAVA) shouldBe false
        ScopeFunctionNesting().def.appliesTo.matches(Lang.JAVA) shouldBe false
    }

    // --- Size and token lenses ---

    @Test
    fun `source-lines-of-code counts the non-blank body lines`() {
        // straight() has three statement lines plus the brace lines of the block body.
        val sloc = measure(SourceLinesOfCode(), kotlin, "straight")!!
        (sloc >= 3.0) shouldBe true
    }

    @Test
    fun `method-length is at least the source-lines count`() {
        val sloc = measure(SourceLinesOfCode(), kotlin, "straight")!!
        val len = measure(MethodLength(), kotlin, "straight")!!
        (len >= sloc) shouldBe true
    }

    @Test
    fun `halstead volume is positive for a non-empty body`() {
        (measure(HalsteadVolume(), kotlin, "straight")!! > 0.0) shouldBe true
    }

    @Test
    fun `maintainability index is in range and higher for the simpler method`() {
        val simple = measure(MaintainabilityIndex(), kotlin, "straight")!!
        val complex = measure(MaintainabilityIndex(), kotlin, "whenFour")!!
        (simple in 0.0..100.0) shouldBe true
        (complex in 0.0..100.0) shouldBe true
        (simple > complex) shouldBe true
    }

    // --- File-level lenses (Kotlin-scoped) ---

    @Test
    fun `file lenses count the top-level declarations`() {
        val ctx = MeasureContext(fixture.classifier(kotlin.lang), kotlin)
        // KShapes.kt declares exactly one top-level type and no loose functions/properties.
        TopLevelDeclarationsPerFile().measure(kotlin, ctx) shouldBe 1.0
        TypesPerFile().measure(kotlin, ctx) shouldBe 1.0
    }
}
