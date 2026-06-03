package dev.ktrics.ir

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Language dispatch keys: extension/id lookup and abstract-kind classification. */
class LangTest {
    @Test
    fun `fromExtension maps java and kotlin extensions case-insensitively`() {
        Lang.fromExtension("java") shouldBe Lang.JAVA
        Lang.fromExtension("JAVA") shouldBe Lang.JAVA
        Lang.fromExtension("kt") shouldBe Lang.KOTLIN
        Lang.fromExtension("kts") shouldBe Lang.KOTLIN
    }

    @Test
    fun `fromExtension rejects an unknown extension`() {
        Lang.fromExtension("scala").shouldBeNull()
    }

    @Test
    fun `fromId resolves the wire id`() {
        Lang.fromId("kotlin") shouldBe Lang.KOTLIN
        Lang.fromId("Java") shouldBe Lang.JAVA
        Lang.fromId("nope").shouldBeNull()
    }

    @Test
    fun `interfaces and annotations are inherently abstract for Martin abstractness`() {
        TypeKind.INTERFACE.isInherentlyAbstract shouldBe true
        TypeKind.ANNOTATION.isInherentlyAbstract shouldBe true
        TypeKind.CLASS.isInherentlyAbstract shouldBe false
        TypeKind.ENUM.isInherentlyAbstract shouldBe false
    }
}
