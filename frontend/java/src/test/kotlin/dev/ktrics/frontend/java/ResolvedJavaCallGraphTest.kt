package dev.ktrics.frontend.java

import dev.ktrics.ir.Lang
import dev.ktrics.testsession.SessionFixture
import dev.ktrics.testsession.repoRoot
import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The Java mirror of `ResolvedCallGraphTest`: over the `app → core` spike, the resolved Java classifier
 * resolves a Java→Kotlin call to its owner-qualified key in the shared symbol space.
 * This is the make-or-break cross-language edge proven from the Java side.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResolvedJavaCallGraphTest {
    private lateinit var fixture: SessionFixture

    @BeforeAll
    fun setUp() {
        val graph =
            SessionFixture.singleModule(
                srcRoots = listOf("core/src/main/kotlin", "core/src/main/java", "app/src/main/kotlin"),
            )
        fixture = SessionFixture(graph, repoRoot().resolve("testdata/spike"), resolved = true)
    }

    @AfterAll
    fun tearDown() = fixture.close()

    @Test
    fun `a java to kotlin call resolves to its owner-qualified key`() {
        val javaFile = fixture.session.javaFiles.first { it.name == "JavaUsesKotlin.java" }
        val refs = fixture.classifier(Lang.JAVA).outgoingRefNames(javaFile)
        // JavaUsesKotlin.pong() calls Kotlin CoreApi.ping(); the resolved key carries the Kotlin owner.
        refs shouldContain "com.example.core.CoreApi.ping"
    }
}
