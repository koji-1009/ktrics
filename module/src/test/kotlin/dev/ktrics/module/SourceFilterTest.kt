package dev.ktrics.module

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Source-file selection: real source extensions, never Gradle build scripts (see doc/calibration.md). */
class SourceFilterTest {
    @Test
    fun `build scripts are recognised by name`() {
        SourceFilter.isBuildScript("build.gradle.kts") shouldBe true
        SourceFilter.isBuildScript("settings.gradle.kts") shouldBe true
        SourceFilter.isBuildScript("Foo.kt") shouldBe false
        SourceFilter.isBuildScript("build.gradle") shouldBe false // groovy script, not a .gradle.kts
    }

    @Test
    fun `isAnalyzable accepts source files and rejects build scripts and non-source`() {
        val tmp = createTempDirectory("srcfilter").toFile()
        try {
            fun f(name: String) = File(tmp, name).apply { writeText("x") }
            SourceFilter.isAnalyzable(f("Foo.kt")) shouldBe true
            SourceFilter.isAnalyzable(f("Bar.java")) shouldBe true
            SourceFilter.isAnalyzable(f("script.kts")) shouldBe true
            SourceFilter.isAnalyzable(f("build.gradle.kts")) shouldBe false // a .gradle.kts is excluded
            SourceFilter.isAnalyzable(f("notes.txt")) shouldBe false // not a source extension
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a directory or missing file is not analyzable`() {
        val tmp = createTempDirectory("srcfilter").toFile()
        try {
            SourceFilter.isAnalyzable(tmp) shouldBe false // a directory, not a file
            SourceFilter.isAnalyzable(File(tmp, "ghost.kt")) shouldBe false // does not exist
        } finally {
            tmp.deleteRecursively()
        }
    }
}
