package dev.ktrics.config

import dev.ktrics.ir.TypeKind
import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.testsupport.fnDecl
import dev.ktrics.testsupport.sourceUnit
import dev.ktrics.testsupport.typeDecl
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Idiom-aware skips: compose, data-class, Lombok @Builder, test files. */
class ConfigSkipPolicyTest {
    private val nesting = BuiltinMetrics.def("maximum-nesting-level")!!
    private val methodLength = BuiltinMetrics.def("method-length")!!
    private val booleanTrap = BuiltinMetrics.def("boolean-trap")!!
    private val nparams = BuiltinMetrics.def("number-of-parameters")!!
    private val cyclomatic = BuiltinMetrics.def("cyclomatic-complexity")!!
    private val classLength = BuiltinMetrics.def("class-length")!!
    private val sloc = BuiltinMetrics.def("source-lines-of-code")!!

    private val plainUnit = sourceUnit(path = "src/main/kotlin/Foo.kt")
    private val testUnit = sourceUnit(path = "src/test/kotlin/FooTest.kt")

    @Test
    fun `compose Preview skips every lens but only when compose is enabled`() {
        val preview = fnDecl("PreviewFoo", annotations = listOf("androidx.compose.ui.tooling.preview.Preview"))
        ConfigSkipPolicy(KtricsConfig(compose = true)).skipFunction(nesting, preview, null, plainUnit) shouldBe true
        // Same function, compose disabled → not skipped.
        ConfigSkipPolicy(KtricsConfig(compose = false)).skipFunction(nesting, preview, null, plainUnit) shouldBe false
    }

    @Test
    fun `compose Composable skips nesting and length but keeps complexity`() {
        val composable = fnDecl("Screen", annotations = listOf("androidx.compose.runtime.Composable"))
        val policy = ConfigSkipPolicy(KtricsConfig(compose = true))
        policy.skipFunction(nesting, composable, null, plainUnit) shouldBe true
        policy.skipFunction(methodLength, composable, null, plainUnit) shouldBe true
        // Behavioural lenses still apply to a @Composable.
        policy.skipFunction(cyclomatic, composable, null, plainUnit) shouldBe false
    }

    @Test
    fun `boolean-trap is skipped on a data-class but not on a regular class`() {
        val dataClass = typeDecl("Point", isData = true)
        val regular = typeDecl("Service", isData = false)
        val ctor = fnDecl("<init>", isConstructor = true)
        val policy = ConfigSkipPolicy(KtricsConfig())
        policy.skipFunction(booleanTrap, ctor, dataClass, plainUnit) shouldBe true
        policy.skipFunction(booleanTrap, ctor, regular, plainUnit) shouldBe false
    }

    @Test
    fun `number-of-parameters is skipped on a Lombok Builder constructor`() {
        val policy = ConfigSkipPolicy(KtricsConfig())
        // (a) the constructor itself carries @Builder
        val annotatedCtor = fnDecl("<init>", isConstructor = true, annotations = listOf("lombok.Builder"))
        policy.skipFunction(nparams, annotatedCtor, null, plainUnit) shouldBe true

        // (b) the owning type carries @Builder
        val builderOwner = typeDecl("Config", annotations = listOf("lombok.Builder"))
        val plainCtor = fnDecl("<init>", isConstructor = true)
        policy.skipFunction(nparams, plainCtor, builderOwner, plainUnit) shouldBe true

        // (c) the owning type declares a nested Builder
        val withNestedBuilder = typeDecl("Config2", nested = listOf(typeDecl("Builder")))
        policy.skipFunction(nparams, plainCtor, withNestedBuilder, plainUnit) shouldBe true

        // A non-constructor never qualifies, even if @Builder is around.
        val method = fnDecl("build", annotations = listOf("lombok.Builder"))
        policy.skipFunction(nparams, method, null, plainUnit) shouldBe false
    }

    @Test
    fun `test files skip size and shape lenses only when test mode is on`() {
        val fn = fnDecl("testThing")
        val type = typeDecl("FooTest")
        val withTest = ConfigSkipPolicy(KtricsConfig(test = true))
        val noTest = ConfigSkipPolicy(KtricsConfig(test = false))

        withTest.skipFunction(sloc, fn, type, testUnit) shouldBe true
        withTest.skipType(classLength, type, testUnit) shouldBe true
        withTest.skipFile(sloc, testUnit) shouldBe true
        // Behavioural lens is never skipped, even in a test file.
        withTest.skipFunction(cyclomatic, fn, type, testUnit) shouldBe false
        // Test mode off → nothing skipped on the same file.
        noTest.skipFunction(sloc, fn, type, testUnit) shouldBe false
    }

    @Test
    fun `test-file detection requires BOTH a test directory and a Test-suffixed name`() {
        val policy = ConfigSkipPolicy(KtricsConfig(test = true))
        // androidTest dir + `Tests` suffix → a test file.
        policy.skipFile(sloc, sourceUnit(path = "app/src/androidTest/kotlin/FooTests.kt")) shouldBe true
        // a `test/`-prefixed path + `Test` suffix → a test file.
        policy.skipFile(sloc, sourceUnit(path = "test/BarTest.kt")) shouldBe true
        // inside /test/ but NOT *Test-named → the name half of the AND fails → NOT a test file.
        policy.skipFile(sloc, sourceUnit(path = "src/test/kotlin/Helper.kt")) shouldBe false
    }

    @Test
    fun `a non-test file is never subject to the test skips`() {
        val policy = ConfigSkipPolicy(KtricsConfig(test = true))
        // plainUnit is under src/main and not named *Test, so the test skips do not apply.
        policy.skipFile(sloc, plainUnit) shouldBe false
    }

    @Test
    fun `class-length and number-of-methods are skipped on a Java record`() {
        val record = typeDecl("PointRecord", kind = TypeKind.RECORD)
        ConfigSkipPolicy(KtricsConfig()).skipType(classLength, record, plainUnit) shouldBe true
    }

    @Test
    fun `a regular production type in a non-test file is not type-skipped`() {
        // Neither data-like nor a test file → skipType falls through to false.
        val regular = typeDecl("Service", isData = false)
        ConfigSkipPolicy(KtricsConfig(test = true)).skipType(classLength, regular, plainUnit) shouldBe false
    }
}
