package dev.ktrics.coverage

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a JaCoCo XML report into [CoverageData] (JaCoCo is the primary input). Branch
 * counters are read directly. The DOCTYPE/DTD is not fetched (offline + safe). Class names are
 * normalised from JaCoCo's `com/example/Foo` to `com.example.Foo` so scopes match the IR.
 */
object JacocoParser {
    fun parse(file: File): CoverageData {
        if (!file.isFile) return CoverageData.EMPTY
        return parse(file.readText())
    }

    fun parse(xml: String): CoverageData {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                // No network, no entity expansion: JaCoCo XML carries a DOCTYPE we must not resolve.
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                // Caps INTERNAL entity expansion too (billion-laughs); doctype itself stays allowed
                // because real JaCoCo reports carry one.
                setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isValidating = false
            }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val result = HashMap<String, MethodCoverage>()
        val classes = doc.getElementsByTagName("class")
        for (i in 0 until classes.length) {
            val classEl = classes.item(i) as? Element ?: continue
            // Binary name → dotted source name: `/` for packages, `$` for nested classes. Both must be
            // dots so the key matches the IR scope (`com.example.Outer.Inner.m`, not `Outer$Inner.m`).
            val className = classEl.getAttribute("name").replace('/', '.').replace('$', '.')
            val methods = classEl.getElementsByTagName("method")
            for (j in 0 until methods.length) {
                val methodEl = methods.item(j) as? Element ?: continue
                if (methodEl.parentNode !== classEl) continue // only direct method children of this class
                val methodName = methodEl.getAttribute("name") // JaCoCo's `<init>` matches the IR constructor name as-is
                // The IR scope key is unsignatured (`Owner.method`), so overloads share one key. JaCoCo
                // emits a separate <method> per overload (disambiguated by `desc`); aggregate their
                // counters instead of letting the last one silently overwrite the rest.
                val key = "$className.$methodName"
                val cov = methodCoverage(methodEl)
                result[key] = result[key]?.plus(cov) ?: cov
            }
        }
        return CoverageData(result)
    }

    private fun methodCoverage(method: Element): MethodCoverage {
        var branchCovered = 0
        var branchMissed = 0
        var lineCovered = 0
        var lineMissed = 0
        val counters = method.getElementsByTagName("counter")
        for (i in 0 until counters.length) {
            val c = counters.item(i) as? Element ?: continue
            val covered = c.getAttribute("covered").toIntOrNull() ?: 0
            val missed = c.getAttribute("missed").toIntOrNull() ?: 0
            when (c.getAttribute("type")) {
                "BRANCH" -> {
                    branchCovered = covered
                    branchMissed = missed
                }
                "LINE" -> {
                    lineCovered = covered
                    lineMissed = missed
                }
            }
        }
        return MethodCoverage(branchCovered, branchMissed, lineCovered, lineMissed)
    }
}
