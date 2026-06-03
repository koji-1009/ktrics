rootProject.name = "ktrics"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Analysis API Standalone plugin coordinates resolve from the JetBrains repos.
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        // Kotlin Analysis API Standalone + the embedded IntelliJ platform live here (PINNED).
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-ide-plugin-dependencies")
    }
}

// --- Modules: One Kotlin codebase, two output artifacts (client native-image, daemon jlink). ---
include(
    // GraalVM native-image entry; argv/cwd relay; links NONE of the platform.
    ":client",
    // ktricsd: socket server, warm session, index + snapshot cache.
    ":daemon",
    // orchestration: file collection, parallelism, module-aware project index.
    ":engine",
    // normalized, language-agnostic model.
    ":ir",
    // KaModule graph model: declared modules, dependency edges, classpath resolution.
    ":module",
    // NodeClassifier (over PsiElement) + IR-lowering interfaces.
    ":lang-api",
    // K2/JetBrains platform bootstrap: standalone session over the KaModule graph.
    ":frontend",
    // Kotlin PSI + Analysis API -> IR; PsiElement classifier (Kotlin dispatch).
    ":frontend:kotlin",
    // Java PSI -> IR; PsiElement classifier (Java dispatch).
    ":frontend:java",
    // ALL calculators, written once against ir + NodeClassifier.
    ":metric",
    // public-API reachability (resolution-backed when available).
    ":unused",
    // console | json | md | ai | sarif.
    ":report",
    // ktrics.yaml loader + schema validation (doctor).
    ":config",
    // git shell-out: --since, regression, snapshot.
    ":vcs",
    // JaCoCo XML primary; lcov/Kover secondary.
    ":coverage",
    // comment + YAML sidecar channels.
    ":dismiss",
    // --- Test-only fixtures. Never shipped. ---
    // platform-free shared fixtures: IR builders, FakeClassifier, GoldenAssert.
    ":test-support",
    // heavy SessionFixture: real standalone session -> IR lowering for metric/frontend tests.
    ":test-session",
)

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
