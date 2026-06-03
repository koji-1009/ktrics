# ktrics

Code-quality metrics for **Java and Kotlin** — the AI-loop counterpart of `dart analyze` / `cargo clippy`. A direct sibling of [`koji-1009/dartrics`](https://github.com/koji-1009/dartrics) (Dart) and [`koji-1009/cargo-rustics`](https://github.com/koji-1009/cargo-rustics) (Rust).

> **AI agents — start here:** run `ktrics ai-loop` before driving the tool. It is the operational playbook — the shell commands you actually run, how to pipe `--reporter ai` into Claude / Cursor / Codex / Aider / OpenHands, the four-station refactor walkthrough, and the dismiss-comment syntax. `ktrics manual` is the conceptual companion: lens design, the decision tree, flag catalogue, loop-mode caveats. Both ship embedded in the binary.

ktrics **measures, it does not gate.** Each metric is an independent *lens* anchored to a primary source; accept / refactor / dismiss stays in your loop. No metric blocks another. By default the **function-level** lenses fire as warnings; the class- and package-level lenses are **measure-only** (set a threshold in `ktrics.yaml` to gate them) — this keeps the failing set tight and actionable.

## How it works

```
┌─────────────────────────┐         ┌──────────────────────────────────────┐
│  ktrics (client)        │  socket │  ktricsd (daemon, warm JVM)           │
│  GraalVM native-image   │ ──────► │  embeds the K2/JetBrains platform:     │
│  ~ms start, single bin, │ ◄────── │  Kotlin Analysis API + Java PSI, one   │
│  NO platform linked     │ stdio + │  symbol space; cross-file index +      │
│  relays argv/cwd        │ exit    │  snapshot; parse→metrics→report inproc │
└─────────────────────────┘         └──────────────────────────────────────┘
```

One embedded platform reads **both languages into one symbol space**, so `Kotlin → Java` and `Java → Kotlin` references resolve in either direction. The native client links none of the platform (millisecond start); the warm daemon keeps the module-aware index in memory so every loop iteration is sub-second.

## Quick start

```bash
ktrics analyze . --reporter ai          # the primary integration surface for agents
ktrics rules                            # the full metric catalogue
ktrics inspect Parser.parse --direction up --depth 2   # walk the call graph around a symbol
ktrics regression --before HEAD~1 --after HEAD --reporter ai
ktrics unused                           # public-API reachability
ktrics doctor                           # validate ktrics.yaml
```

Exit codes (sysexits): `0` clean · `1` violations with `--fatal-warnings` · `64` usage · `65` bad input / unresolved git ref · `70` internal · `78` bad config.

## Metric catalogue

**Function level** (both languages) — these **gate** by default: cyclomatic-complexity (10), cognitive-complexity (15), maximum-nesting-level (4), number-of-parameters (4 / Kotlin 6), boolean-trap (2), source-lines-of-code (60). Off / measure-only by default: halstead-volume, maintainability-index, npath-complexity (its product-form estimate over-counts independent guard clauses, so cyclomatic + cognitive carry the branch-complexity gate; set a threshold to enforce it). Kotlin-only: not-null-assertion-density (`!!`, 3), scope-function-nesting (2).

**Class level** — the CK suite (full strength on Java), all **measure-only** by default: number-of-methods, weighted-methods-per-class, lcom4, coupling-between-objects, response-for-class, depth-of-inheritance-tree, number-of-children, class-length. **File level** (Kotlin): top-level-declarations-per-file (10), types-per-file (informational).

**Package level** (Martin), all measure-only / informational: efferent-coupling, afferent-coupling, instability, abstractness, distance-from-main-sequence.

Class- and package-level lenses are measure-only because, as dogfooding confirmed, project-wide structural thresholds are noisy as hard gates; opt into one via `ktrics.yaml` (`metrics: { lcom4: { warning: 3 } }`). The set that **fires** is larger for Java and differs in composition for Kotlin — by construction, governed per-metric by `appliesTo` (structural validity), not as a tuning knob. When a metric doesn't apply, `rules`/`ai` auto-explain says *why*. Calibration rationale and deviations live in [`doc/calibration.md`](doc/calibration.md).

## Build

```bash
./gradlew build                    # all modules
./gradlew :client:nativeCompile    # GraalVM native-image client
./gradlew :daemon:installDist      # self-contained daemon
./gradlew test                     # golden + unit tests
```

Requires JDK 21. The Kotlin Analysis API Standalone + IntelliJ platform resolve from the JetBrains repositories (pinned in `gradle/libs.versions.toml`).

## Honest limitations (v0)

- **Resolution is on by default within the project; external edges need the classpath.** A reference into a dependency not on the classpath degrades *that single edge* to name-based, flagged via the `resolution` field. In-project and cross-language (Kotlin↔Java) edges resolve without a build.
- **Java analysis depends on the standalone session materializing Java PSI bodies + Java symbols** — the foundational validation check (`Phase0SpikeTest`). This is the least-trodden part of the Analysis API Standalone; the version is pinned and upgrades are gated by the CI resolution corpus.
- **Kotlin Analysis API Standalone is version-locked to the Kotlin release** and not yet a fully supported standalone use case; pinned, upgrades gated.
- **Multi-module is first-class; module discovery is staged.** The graph drives the session, cross-module resolution, package boundaries and unused-reachability from v1. v1 requires the graph to be *declared* (`ktrics.yaml` / `--module`); v2 auto-derives it via Gradle/Maven. Kotlin Multiplatform source sets and `expect`/`actual` are a v1 limitation — model the JVM source sets, state the gap.
- **Daemon mode assumes process + filesystem persist across the loop.** Fully ephemeral container-per-call CI loses warmth; use CRaC (Linux) or accept one cold platform start.
- **The native-image client links none of the platform** — any path needing analysis goes through the daemon, never the client.
- Field names are stable through `0.x`, not yet externally stress-tested — pin a version in CI.
- The built-in metric set is curated, not exhaustive; Halstead / MI are off by default.

## Repository status note

**Verified green**: `./gradlew test` compiles every module (16 shipping + 2 test-only) against the real pinned Analysis API (2.1.20) and passes every unit test. (Building needs network access to Maven Central + the JetBrains repos.)

The foundational spike (`frontend/.../Phase0SpikeTest.kt`) proves **all** the make-or-break facts — Kotlin PSI loads, **Java PSI bodies materialize**, **Kotlin→Java** resolves (Java *source* included), **Java→Kotlin** resolves, and **Kotlin→Kotlin cross-module** resolves. The single-host, one-symbol-space design holds in full; cross-language Kotlin↔Java resolution is real in both directions.

The Kotlin→Java edge posed an initial risk. It briefly *appeared* unresolvable, but root-causing it found a self-inflicted bug, not a platform limitation: the session never registered the **JDK as a `KaSdkModule`**, and the FIR Java symbol provider is rooted on the JDK — so without it Kotlin could resolve *no* Java at all (not the JDK, not source, not even a compiled JAR). Adding the JDK SDK module (`buildKtSdkModule { addBinaryRootsFromJdkHome(...) }`, see `StandaloneSessionFactory.build`) makes every Kotlin→Java edge resolve, source included.

Dependency note: the foundation is the monolithic `org.jetbrains.kotlin:kotlin-compiler` (unrelocated com.intellij PSI + `KotlinCoreEnvironment` + co-located extension XMLs in one jar), with the `analysis-api-*-for-ide` jars (`isTransitive=false`) layered on for the K2 API.
