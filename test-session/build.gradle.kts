// Heavy real-session test harness. Builds a standalone Analysis API session over a small testdata
// project and lowers it to IR + the resolved classifiers — the foundation the metric and frontend
// tests are mass-produced on. Links the full platform (via the frontends), so it is kept OUT of
// :test-support to keep pure-logic tests session-free and fast.
dependencies {
    api(project(":test-support"))
    api(project(":module"))
    api(project(":frontend"))
    api(project(":frontend:kotlin"))
    api(project(":frontend:java"))
    // The fixture base class wires JUnit @BeforeAll/@AfterAll lifecycle, so JUnit is part of its surface.
    api(libs.junit.jupiter)
}
