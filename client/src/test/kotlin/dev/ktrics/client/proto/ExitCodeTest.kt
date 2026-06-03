package dev.ktrics.client.proto

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** sysexits mapping shared verbatim by client and daemon. */
class ExitCodeTest {
    @Test
    fun `the sysexits numbers match the convention`() {
        ExitCode.OK.code shouldBe 0
        ExitCode.VIOLATIONS.code shouldBe 1
        ExitCode.USAGE.code shouldBe 64
        ExitCode.BAD_INPUT.code shouldBe 65
        ExitCode.INTERNAL.code shouldBe 70
        ExitCode.BAD_CONFIG.code shouldBe 78
    }

    @Test
    fun `of resolves a known code back to its enum`() {
        ExitCode.of(0) shouldBe ExitCode.OK
        ExitCode.of(64) shouldBe ExitCode.USAGE
        ExitCode.of(78) shouldBe ExitCode.BAD_CONFIG
    }

    @Test
    fun `of returns null for an unmapped code`() {
        ExitCode.of(13).shouldBeNull()
        ExitCode.of(-1).shouldBeNull()
    }

    @Test
    fun `the codes are distinct`() {
        val codes = ExitCode.entries.map { it.code }
        codes.toSet().size shouldBe codes.size
    }
}
