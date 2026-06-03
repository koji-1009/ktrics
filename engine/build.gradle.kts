// Orchestration: file collection, parallelism, module-aware project index. Wires the
// frontends, metric engine, unused detector, coverage, vcs, config, dismiss and reporters into the
// analyze/unused/regression/report pipelines. Daemon-side; never linked by the native client.
dependencies {
    api(project(":ir"))
    api(project(":module"))
    api(project(":metric"))
    api(project(":report"))
    api(project(":config"))
    api(project(":frontend"))
    api(project(":frontend:kotlin"))
    api(project(":frontend:java"))
    api(project(":unused"))
    api(project(":coverage"))
    api(project(":vcs"))
    api(project(":dismiss"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(project(":test-support"))
    testImplementation(project(":test-session"))
}

// Embed the operator docs (doc/*.md) into the binary so `ktrics manual` / `ai-loop` ship with the
// tool and never drift from the version in use.
sourceSets {
    named("main") {
        resources.srcDir(rootProject.file("doc"))
    }
}
