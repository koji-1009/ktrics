import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover) apply false
}

allprojects {
    group = "io.github.koji-1009.ktrics"
    version = providers.gradleProperty("ktrics.version").getOrElse("0.1.0-SNAPSHOT")
}

// Every module is Kotlin/JVM 21 with the same lint + test conventions.
// The daemon-side modules link the platform; the client links none of it — enforced by its own
// build script and a native-image reflection-config CI check, not here.
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    // Shared detekt config: tunes the overly-strict defaults to this project's deliberate conventions
    // (line length aligned to ktlint's 140, guard-clause return counts, IR-carrier parameter lists,
    // topic-based file names). Genuine smells stay enforced.
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    }

    repositories {
        mavenCentral()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-ide-plugin-dependencies")
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            // Quality is enforced by detekt + ktlint; kotlinc warnings-as-errors is left
            // off so a stray platform-API deprecation can't block the build (the pinned Analysis API
            // surface moves between versions). Flip to true once a version is fully validated in CI.
            allWarningsAsErrors.set(false)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Golden tests are the backbone: fail loudly, show the diff.
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.kotest.assertions)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    // --- Coverage via Kover, measured through the JaCoCo agent (see `useJacoco` below).
    // JaCoCo's Kotlin-aware filters auto-drop most compiler-generated members (data-class copy/componentN,
    // equals/hashCode, enum values/valueOf, interface $DefaultImpls), so the report tracks hand-written
    // code far more closely than the deprecated IntelliJ engine did. The only explicit exclusion is the JVM
    // `main` entry shells (`*MainKt`, a one-liner ending in `exitProcess`, delegating to the tested
    // ClientCli/DaemonCli). `check` enforces a 99% LINE floor on every shipped module, held at 100%
    // for the modules already at full coverage; `name` is the project name (":frontend:kotlin" →
    // "kotlin"). The test-only fixtures (:test-support, :test-session) are never shipped and carry no floor.
    val coverageFloorPercent: Int =
        when (name) {
            "test-support", "test-session" -> 0
            "coverage", "dismiss", "lang-api", "unused" -> 100
            else -> 99
        }

    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        // Measure with the JaCoCo agent instead of the (now-deprecated) IntelliJ agent: JaCoCo's
        // Kotlin-aware filters auto-drop most compiler-generated members (data-class copy/componentN,
        // @Serializable serializer()/write$Self, $DefaultImpls, synthetic getters) that the IntelliJ
        // engine counted against us — the Kover maintainers' own recommended path (kotlinx-kover #686,
        // #720, #746). Kover stays the plugin/report frontend; only the underlying engine changes.
        useJacoco("0.8.14")
        reports {
            filters {
                excludes {
                    classes("*MainKt")
                }
            }
            verify {
                rule {
                    minBound(coverageFloorPercent)
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(tasks.named("koverVerify"))
    }
}

// Dogfood: once `analyze` works this becomes `ktrics analyze . --fatal-warnings`.
tasks.register("dogfood") {
    group = "verification"
    description = "Run ktrics on its own source once the analyzer is built."
    dependsOn(":daemon:installDist")
    doLast {
        logger.lifecycle("ktrics dogfood: run `ktrics analyze . --fatal-warnings` against this repo.")
    }
}
