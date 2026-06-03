package dev.ktrics.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Exhaustive parse-branch coverage for the ktrics.yaml loader, asserting CORRECT parsing. */
class ConfigLoaderParseTest {
    @Test
    fun `discover mode parses each keyword and warns on an invalid one`() {
        ConfigLoader.parse("ktrics:\n  modules:\n    discover: gradle\n").modules.discover shouldBe DiscoverMode.GRADLE
        ConfigLoader.parse("ktrics:\n  modules:\n    discover: maven\n").modules.discover shouldBe DiscoverMode.MAVEN
        ConfigLoader.parse("ktrics:\n  modules:\n    discover: off\n").modules.discover shouldBe DiscoverMode.OFF
        val problems = ArrayList<String>()
        val cfg = ConfigLoader.parse("ktrics:\n  modules:\n    discover: nonsense\n", problems)
        cfg.modules.discover shouldBe DiscoverMode.OFF
        problems.any { it.contains("modules.discover") } shouldBe true
    }

    @Test
    fun `resolution mode parses and warns on an invalid one`() {
        ConfigLoader.parse("ktrics:\n  resolution: resolved\n").resolution shouldBe ResolutionMode.RESOLVED
        val problems = ArrayList<String>()
        ConfigLoader.parse("ktrics:\n  resolution: bogus\n", problems).resolution shouldBe ResolutionMode.AUTO
        problems.any { it.contains("resolution") } shouldBe true
    }

    @Test
    fun `a declared module is fully parsed and a nameless one is a problem`() {
        val cfg =
            ConfigLoader.parse(
                """
                ktrics:
                  modules:
                    declared:
                      - name: app
                        srcRoots: [app/src/main/kotlin, app/src/main/java]
                        classpath: [libs/dep.jar]
                        dependsOn: [core]
                """.trimIndent(),
            )
        val app = cfg.modules.declared.single()
        app.name shouldBe "app"
        app.srcRoots shouldContainExactly listOf("app/src/main/kotlin", "app/src/main/java")
        app.classpath shouldContainExactly listOf("libs/dep.jar")
        app.dependsOn shouldContainExactly listOf("core")

        val problems = ArrayList<String>()
        ConfigLoader.parse("ktrics:\n  modules:\n    declared:\n      - srcRoots: [x]\n", problems)
        problems.any { it.contains("module without a name") } shouldBe true
    }

    @Test
    fun `a metric scalar false or true sets enabled, and a junk scalar is a problem`() {
        ConfigLoader.parse("ktrics:\n  metrics:\n    lcom4: false\n").metrics["lcom4"]!!.enabled shouldBe false
        ConfigLoader.parse("ktrics:\n  metrics:\n    lcom4: true\n").metrics["lcom4"]!!.enabled shouldBe true
        val problems = ArrayList<String>()
        ConfigLoader.parse("ktrics:\n  metrics:\n    lcom4: sometimes\n", problems)
        problems.any { it.contains("unexpected scalar") } shouldBe true
    }

    @Test
    fun `an object-form metric parses enabled, both-language and per-language thresholds`() {
        val cfg =
            ConfigLoader.parse(
                """
                ktrics:
                  metrics:
                    cyclomatic-complexity:
                      enabled: true
                      warning: 10
                      error: 20
                      java:
                        warning: 8
                        error: 16
                      kotlin:
                        warning: 12
                """.trimIndent(),
            )
        val entry = cfg.metrics["cyclomatic-complexity"]!!
        entry.enabled shouldBe true
        entry.warning shouldBe 10.0
        entry.error shouldBe 20.0
        entry.java!!.warning shouldBe 8.0
        entry.java!!.error shouldBe 16.0
        entry.kotlin!!.warning shouldBe 12.0
    }

    @Test
    fun `a parse problem is the exact message, emitted exactly once`() {
        // Loose `.any { contains(...) }` would pass on a duplicated or value-swapped message; pin it exactly.
        val problems = ArrayList<String>()
        ConfigLoader.parse("ktrics:\n  modules:\n    discover: nonsense\n", problems)
        problems shouldContainExactly listOf("invalid value 'nonsense' for 'modules.discover'")
    }

    @Test
    fun `an object metric with non-numeric thresholds resolves them to null, not a crash`() {
        // The lenient node-walking accessors must tolerate a wrong-typed value (return null), keeping the entry.
        val cfg =
            ConfigLoader.parse(
                """
                ktrics:
                  metrics:
                    cyclomatic-complexity:
                      enabled: "yes"
                      warning: "abc"
                """.trimIndent(),
            )
        val entry = cfg.metrics["cyclomatic-complexity"]!!
        entry.warning shouldBe null // non-numeric → null
        entry.enabled shouldBe null // not a strict boolean → null
    }

    @Test
    fun `a metric whose value is neither a scalar nor a map is ignored`() {
        // A list-valued metric entry matches neither the scalar nor the object form → dropped, no entry.
        val cfg = ConfigLoader.parse("ktrics:\n  metrics:\n    cyclomatic-complexity:\n      - 1\n      - 2\n")
        cfg.metrics.containsKey("cyclomatic-complexity") shouldBe false
    }

    @Test
    fun `find with no explicit argument discovers ktrics-yaml`() {
        val dir = createTempDirectory("cfg").toFile()
        try {
            File(dir, "ktrics.yaml").writeText("ktrics: {}\n")
            // A one-arg call exercises the `explicit = null` default parameter.
            ConfigLoader.find(dir)!!.name shouldBe "ktrics.yaml"
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an explicit config path that does not exist falls back to discovery`() {
        val dir = createTempDirectory("cfg").toFile()
        try {
            // The explicit file is absent → find() returns null and load() yields the defaults.
            val load = ConfigLoader.load(dir, File(dir, "ghost.yaml"))
            load.source shouldBe null
            load.config shouldBe KtricsConfig.DEFAULT
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unused config parses entry points, ignore-annotations and presets`() {
        val cfg =
            ConfigLoader.parse(
                """
                ktrics:
                  unused:
                    entry-points: [main, "@Custom"]
                    ignore-annotations: [Generated]
                    presets: [spring, jpa]
                """.trimIndent(),
            )
        cfg.unused.entryPoints shouldContainExactly listOf("main", "@Custom")
        cfg.unused.ignoreAnnotations shouldContainExactly listOf("Generated")
        cfg.unused.presets shouldContainExactly listOf("spring", "jpa")
    }

    @Test
    fun `unused entry points fall back to the defaults when none are listed`() {
        // An empty unused block keeps the built-in entry points rather than clearing them.
        val cfg = ConfigLoader.parse("ktrics:\n  unused:\n    presets: [spring]\n")
        cfg.unused.entryPoints shouldContainExactly listOf("main", "@Test")
    }

    @Test
    fun `exclude globs and snapshot mode parse`() {
        val cfg =
            ConfigLoader.parse(
                "ktrics:\n  exclude: [\"**/build/**\", \"**/gen/**\"]\n  snapshot:\n    mode: cache\n",
            )
        cfg.exclude shouldContainExactly listOf("**/build/**", "**/gen/**")
        cfg.snapshot.mode shouldBe "cache"
    }

    @Test
    fun `an unknown top-level key is silently ignored, not reported as a problem`() {
        // Characterises the ACTUAL behavior: only unknown enum VALUES are collected as problems; a stray
        // top-level key is ignored. (The loader's KDoc overstates this — flagged in review.)
        val problems = ArrayList<String>()
        val cfg = ConfigLoader.parse("ktrics:\n  bogusKey: true\n  compose: true\n", problems)
        cfg.compose shouldBe true // the known key still parses
        problems.shouldBeEmpty() // the unknown key produced no diagnostic
    }

    @Test
    fun `config without the ktrics wrapper key is read at the root`() {
        // The loader accepts both `ktrics: { ... }` and a bare top-level config.
        ConfigLoader.parse("compose: true\n").compose shouldBe true
    }

    @Test
    fun `non-map yaml falls back to the defaults`() {
        ConfigLoader.parse("- just\n- a\n- list\n") shouldBe KtricsConfig.DEFAULT
    }

    @Test
    fun `an empty config file falls back to the defaults without crashing`() {
        val dir = createTempDirectory("cfg").toFile()
        try {
            File(dir, "ktrics.yaml").writeText("")
            // An empty document must degrade to defaults (via parse-failure recovery), not throw.
            ConfigLoader.load(dir).config shouldBe KtricsConfig.DEFAULT
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `load finds ktrics-yml when ktrics-yaml is absent`() {
        val dir = createTempDirectory("cfg").toFile()
        try {
            File(dir, "ktrics.yml").writeText("ktrics:\n  compose: true\n")
            val load = ConfigLoader.load(dir)
            load.source!!.name shouldBe "ktrics.yml"
            load.config.compose shouldBe true
            load.hash.isNotBlank() shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `load returns defaults with no source when no config file exists`() {
        val dir = createTempDirectory("cfg").toFile()
        try {
            val load = ConfigLoader.load(dir)
            load.source shouldBe null
            load.config shouldBe KtricsConfig.DEFAULT
            load.problems shouldBe emptyList()
            load.hash shouldBe ""
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `load records a parse failure as a problem and falls back to defaults`() {
        val dir = createTempDirectory("cfg").toFile()
        try {
            // Tab indentation is invalid YAML → the loader records the failure rather than throwing.
            File(dir, "ktrics.yaml").writeText("ktrics:\n\tmetrics: oops\n")
            val load = ConfigLoader.load(dir)
            load.problems.any { it.contains("failed to parse") } shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an explicit config path overrides discovery`() {
        val dir = createTempDirectory("cfg").toFile()
        try {
            val explicit = File(dir, "custom.yaml").apply { writeText("ktrics:\n  test: true\n") }
            val load = ConfigLoader.load(dir, explicit)
            load.source shouldBe explicit
            load.config.test shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }
}
