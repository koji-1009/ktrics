package dev.ktrics.coverage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class JacocoParserTest {
    private val xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <report name="t">
          <package name="com/example">
            <class name="com/example/Foo" sourcefilename="Foo.java">
              <method name="wellTested" desc="()V" line="5">
                <counter type="BRANCH" missed="1" covered="9"/>
                <counter type="LINE" missed="0" covered="4"/>
              </method>
              <method name="untested" desc="()V" line="20">
                <counter type="BRANCH" missed="8" covered="2"/>
                <counter type="LINE" missed="6" covered="0"/>
              </method>
              <method name="noBranches" desc="()V" line="30">
                <counter type="LINE" missed="0" covered="3"/>
              </method>
            </class>
          </package>
        </report>
        """.trimIndent()

    @Test
    fun `complexityJustified is true for high branch coverage`() {
        val data = JacocoParser.parse(xml)
        data.complexityJustified("com.example.Foo.wellTested") shouldBe true // 9/10 branch
        data.complexityJustified("com.example.Foo.untested") shouldBe false // 2/10 branch
    }

    @Test
    fun `falls back to line coverage when there are no branches`() {
        val data = JacocoParser.parse(xml)
        data.complexityJustified("com.example.Foo.noBranches") shouldBe true // 3/3 line
    }

    @Test
    fun `unknown scope is not justified`() {
        JacocoParser.parse(xml).complexityJustified("com.example.Foo.ghost") shouldBe false
    }

    @Test
    fun `a scope carrying a signature suffix still resolves to its coverage`() {
        // The IR scope key is unsignatured, but a caller may pass `Owner.method(desc)`; the parenthesised
        // part is stripped so the lookup still hits.
        val data = JacocoParser.parse(xml)
        data.complexityJustified("com.example.Foo.wellTested(I)V") shouldBe true
        data.complexityJustified("com.example.Foo.wellTested()") shouldBe true
    }

    @Test
    fun `a method nested below a non-direct child is not attributed to the class`() {
        // getElementsByTagName is recursive; the parser's parentNode guard must drop a <method> that is
        // not a DIRECT child. The nested method is fully covered, so without the guard it would be wrongly
        // attributed as `Outer.nested` and reported justified — asserting false proves the guard holds.
        val xml =
            """
            <?xml version="1.0"?>
            <report name="t"><package name="com/example">
              <class name="com/example/Outer">
                <method name="real" desc="()V"><counter type="LINE" missed="0" covered="3"/></method>
                <wrapper>
                  <method name="nested" desc="()V"><counter type="LINE" missed="0" covered="9"/></method>
                </wrapper>
              </class>
            </package></report>
            """.trimIndent()
        val data = JacocoParser.parse(xml)
        data.complexityJustified("com.example.Outer.real") shouldBe true // 3/3 line, a direct child
        data.complexityJustified("com.example.Outer.nested") shouldBe false // guarded out → unknown scope
    }

    @Test
    fun `a report carrying a DOCTYPE parses offline without fetching the DTD`() {
        // Real JaCoCo reports ship a DOCTYPE; the parser disables external-DTD/entity loading, so this must
        // succeed with no network or file access (a regression re-enabling it would throw/hang here).
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="t"><package name="com/example"><class name="com/example/Foo">
            <method name="m" desc="()V"><counter type="LINE" missed="0" covered="2"/></method>
            </class></package></report>
            """.trimIndent()
        JacocoParser.parse(xml).complexityJustified("com.example.Foo.m") shouldBe true
    }

    @Test
    fun `parse from a file reads a report and returns empty for a missing file`() {
        JacocoParser.parse(File("no-such-jacoco-report.xml")) shouldBe CoverageData.EMPTY
        val tmp = File.createTempFile("jacoco", ".xml")
        try {
            tmp.writeText(xml)
            JacocoParser.parse(tmp).complexityJustified("com.example.Foo.wellTested") shouldBe true
        } finally {
            tmp.delete()
        }
    }
}
