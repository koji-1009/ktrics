// Public-API reachability: BFS over the reference graph, spanning module-dependency edges.
// Resolution-backed when the classpath resolves, name-based otherwise. `--apply` deletion runs
// only when reachability was fully resolution-backed (safety gate).
dependencies {
    api(project(":ir"))
    api(project(":lang-api"))
    api(project(":module"))
    implementation(project(":metric"))
    implementation(libs.serialization.json)
}
