// K2/JetBrains platform bootstrap: the Kotlin Analysis API Standalone session built
// over the KaModule graph, one shared symbol space for both languages. This module (and its
// :kotlin / :java children) is the ONLY place that may touch the platform — a platform-isolation invariant.
//
// Cannot be GraalVM-native: the platform's reflection/classloading blocks native-image.
//
// Foundation: the monolithic `kotlin-compiler` (com.intellij PSI + KotlinCoreEnvironment +
// co-located META-INF/extensions/*.xml, all unrelocated). On top of it, the Analysis API `-for-ide`
// jars add the K2 Analysis API (analyze {}, KaSession). The `-for-ide` jars ship POMs that declare
// transitive deps on plain-named artifacts JetBrains does not publish, so each is pulled with
// isTransitive=false. PINNED + upgrade-gated.
dependencies {
    api(project(":ir"))
    api(project(":lang-api"))
    api(project(":module"))

    // The compiler foundation (PSI + standalone environment). Shared, unrelocated com.intellij.
    api(libs.kotlin.compiler)

    // The K2 Analysis API surface (KaSession, analyze {}, KaModule, standalone session builders).
    api(libs.analysis.api) { isTransitive = false }
    api(libs.analysis.api.standalone) { isTransitive = false }
    api(libs.analysis.api.impl.base) { isTransitive = false }
    api(libs.analysis.api.fir) { isTransitive = false }
    api(libs.analysis.api.platform) { isTransitive = false }
    api(libs.low.level.api.fir) { isTransitive = false }
    api(libs.symbol.light.classes) { isTransitive = false }

    implementation(libs.coroutines.core)
    // Analysis API resolution caches on Caffeine; the for-ide jars don't bundle it.
    runtimeOnly(libs.caffeine)
}
