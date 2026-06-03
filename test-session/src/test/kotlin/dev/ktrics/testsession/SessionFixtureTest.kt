package dev.ktrics.testsession

import dev.ktrics.ir.Lang
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Smoke test for the session harness itself: over the `app → core` spike project a [SessionFixture]
 * must build a session, lower both Kotlin and Java files to IR, and resolve a cross-module call edge.
 * If this passes, the metric and frontend tests have a working foundation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionFixtureTest {
    private lateinit var fixture: SessionFixture

    @BeforeAll
    fun setUp() {
        val graph =
            SessionFixture.singleModule(
                srcRoots =
                    listOf(
                        "core/src/main/kotlin",
                        "core/src/main/java",
                        "app/src/main/kotlin",
                    ),
            )
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/spike"), resolved = true)
    }

    @AfterAll
    fun tearDown() = fixture.close()

    @Test
    fun `lowers kotlin files to IR with their declarations`() {
        val app = fixture.kotlinUnit("App.kt")
        app.lang shouldBe Lang.KOTLIN
        app.allFunctions().map { it.name }.toList() shouldContain "run"
    }

    @Test
    fun `lowers java files to IR including method bodies`() {
        val greeter = fixture.javaUnit("JavaGreeter.java")
        greeter.lang shouldBe Lang.JAVA
        greeter.type("JavaGreeter").methods.map { it.name } shouldContain "hello"
    }

    @Test
    fun `the resolved classifier yields owner-qualified outgoing edges`() {
        val appFile = fixture.session.ktFiles.first { it.name == "App.kt" }
        val refs = fixture.classifier(Lang.KOTLIN).outgoingRefNames(appFile)
        // The whole point of the resolved harness: edges carry the owner, not just the simple name.
        refs shouldContain "com.example.core.CoreApi.ping"
    }
}
