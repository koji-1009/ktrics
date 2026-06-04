# ktrics

[![CI](https://github.com/koji-1009/ktrics/actions/workflows/ci.yml/badge.svg)](https://github.com/koji-1009/ktrics/actions/workflows/ci.yml)
[![GitHub license](https://img.shields.io/github/license/koji-1009/ktrics)](https://github.com/koji-1009/ktrics/blob/main/LICENSE)

Code-quality metrics and unused public-API detection for **Java and Kotlin** — one analysis pass over both languages, designed for the AI agent loop.

> **AI agents — start here:** run `ktrics ai-loop` (or read [`doc/ai-loop.md`](doc/ai-loop.md)) before driving the tool. It is the operational playbook — the shell commands you actually run, how to pipe `--reporter ai` into Claude / Cursor / Codex / Aider / OpenHands, the four-station refactor walkthrough, and the dismiss-comment syntax. `ktrics manual` is the conceptual companion: lens design, the decision tree, flag catalogue, loop-mode caveats. Both ship embedded in the binary.

## What it does

ktrics computes a battery of code-quality metrics — McCabe, Cognitive Complexity (Sonar), Chidamber & Kemerer, Martin, Halstead — across Java and Kotlin sources in a single run, alongside a call-graph reachability pass for unreachable public API. One embedded analysis host (the Kotlin Analysis API plus the platform's Java PSI) reads **both languages into one symbol space**, so `Kotlin → Java` and `Java → Kotlin` references resolve in either direction without a build.

ktrics **measures, it does not gate.** Each metric is an independent *lens* anchored to a primary source; accept / refactor / dismiss stays in your loop. No metric blocks another. By default the **function-level** lenses fire as warnings; the class- and package-level lenses are **measure-only** (set a threshold in `ktrics.yaml` to gate them) — this keeps the failing set tight and actionable.

### Resolution-backed by design

Embedding the analysis host carries a real maintenance cost (see [Limitations](#limitations)). It is a deliberate trade, because resolution is what the analysis stands on:

- **Semantic measurements, not name matching.** A reference resolves to the declaration it actually targets — across files, across modules, and across the Kotlin↔Java boundary, with no build step. Coupling, cohesion, and inheritance lenses (CBO, RFC, LCOM4, DIT/NOC, Martin) count resolved edges instead of same-name guesses.
- **Reachability, not text search.** `unused` is a breadth-first sweep from entry points (`main`, `@Test`, configured annotations) over the resolved reference graph, spanning module-dependency edges — a public symbol used only from another module, or only from the other language, is reachable. `inspect` walks the same resolved call graph.
- **Confidence is part of every result.** Resolution-dependent measurements carry a `resolution` stamp; an edge that fails to resolve degrades *that single edge* to name-based, visibly. Destructive operations (`unused --apply`) are gated on a fully RESOLVED sweep.

### Designed for the AI loop

- **Auto-explain by default** — rationale, refactor hints, and primary-source citation ride alongside every fired metric, so an agent reads the *why* without a second tool call.
- **Stable IDs across runs** — every violation carries a 16-hex-char id (`sha256("<file>|<scope>|<metric>")`), reappearing across runs so AI loops can detect "my fix didn't take".
- **Docs in the binary** — `ktrics ai-loop` prints the operational playbook and `ktrics manual` prints the lens reference; no separate doc download.
- **Sub-second iterations** — a warm daemon keeps the module-aware index in memory, so every loop iteration after the first is fast.

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

The native client links none of the analysis platform, so it starts in milliseconds; it relays your command to the daemon, which auto-spawns on demand and stays warm across the loop.

## Install

Download the archive for your platform from [GitHub Releases](https://github.com/koji-1009/ktrics/releases) — `linux-x64` / `macos-arm64` (`.tar.gz`) or `windows-x64` (`.zip`) — extract it, and put its `bin/` on your `PATH`. Each archive bundles the native `ktrics` client and the `ktricsd` daemon; the client locates the daemon as a sibling, so keep the extracted layout intact.

Or build from source (JDK 21 + GraalVM for the native client — see [Development](#development)).

## Quick start

```bash
# Token-efficient report optimised for LLM consumption — the primary integration surface for agents.
ktrics analyze . --reporter ai | claude -p "Refactor the threshold violations; keep behaviour identical."

# After the agent applies a fix: confirm metrics actually improved.
ktrics regression --before HEAD~1 --after HEAD --reporter ai

# Read the operational playbook (start here) or the lens reference in the terminal.
ktrics ai-loop
ktrics manual
```

## Subcommands

| Command | Purpose |
| --- | --- |
| `analyze <path>` | Every enabled metric + the public-API unused detector. Emits `signals:` (call-graph fan-in / fan-out per declaration) as reference information alongside the thresholded metrics. |
| `unused [--apply]` | Public-API reachability only (fast path); `--apply` deletes top-level orphans (safety-gated). |
| `inspect <symbol>` | Walk the resolved call graph around a named declaration; `--depth` / `--direction up\|down\|both` configure the walk. |
| `regression --before <ref> --after <ref>` | Compare metrics between two git states; classify each delta by polarity as improved / regressed / unchanged / added / removed. |
| `report <input.json>` | Re-emit a previously saved JSON report in another format. |
| `rules` | Catalogue every metric, one line each with defaults and applicability. |
| `explain <metric-id>` | Full rationale, refactor hints, and references for one metric. |
| `ai-loop` | Operational playbook: commands, prompts, dismiss syntax — start here for agents (mirrors [`doc/ai-loop.md`](doc/ai-loop.md)). |
| `manual` | Conceptual reference: lens design, decision tree, full flag catalogue (mirrors [`doc/manual.md`](doc/manual.md)). |
| `doctor` | Validate `ktrics.yaml`. |
| `daemon status\|stop` | Daemon lifecycle (auto-spawns on demand). |

Exit codes (sysexits): `0` clean · `1` violations with `--fatal-warnings` · `64` usage · `65` bad input / unresolved git ref · `70` internal · `78` bad config.

## Provided metrics

ktrics ships a curated set anchored to published sources. Each metric exposes `rationale`, `refactorHints`, `references`, and `polarity`, all surfaced through `ktrics rules` and the AI / md / SARIF reporters. Which lenses fire for a file is governed per-metric by `appliesTo` (structural validity, not a tuning knob) — so the firing set is larger for Java and differs in composition for Kotlin, by construction; when a metric doesn't apply, `rules` / `ai` auto-explain says *why*. For the audit trail — selection principles, deviations from the cited definitions, threshold calibration — see [`doc/calibration.md`](doc/calibration.md).

Lenses marked **off** ship disabled by default; opt in via `metrics: { <id>: { enabled: true } }`. A `—` in **Default warning** means the lens emits a measurement but fires no warning until you set a threshold via `metrics: { <id>: { warning: <n> } }`.

### Function / method level

| Metric | Reference | Default warning |
| --- | --- | --- |
| Cyclomatic Complexity | McCabe 1976 | 10 (error 20) |
| Cognitive Complexity | SonarSource 2018 | 15 |
| Maximum Nesting Level | — | 4 |
| Number Of Parameters | Fowler 1999 | 4 (Kotlin 6, error 8) |
| Boolean Trap | McConnell 2004; Bloch 2018 | 2 |
| Source Lines Of Code | Boehm 1981 | 60 |
| Not-Null Assertion Density (Kotlin) | Moskała, Effective Kotlin | 3 |
| Scope Function Nesting (Kotlin) | Moskała, Effective Kotlin | 2 |
| NPath Complexity | Nejmeh 1988 | — |
| Method Length | — | — |
| Halstead Volume **off** | Halstead 1977 | opt-in |
| Maintainability Index **off** | Oman & Hagemeister 1992 | opt-in |

NPath ships measure-only because its product-form estimate over-counts independent guard clauses; cyclomatic + cognitive carry the branch-complexity gate (see [`doc/calibration.md`](doc/calibration.md)).

### Class level

The CK suite (full strength on Java), all measure-only by default — dogfooding confirmed project-wide structural thresholds are noisy as hard gates; opt into one via `ktrics.yaml` (`metrics: { lcom4: { warning: 3 } }`).

| Metric | Reference | Default warning |
| --- | --- | --- |
| Number Of Methods | — | — |
| Weighted Methods Per Class | CK 1994 | — |
| LCOM4 | Hitz & Montazeri 1995 | — |
| Coupling Between Objects | CK 1994 | — |
| Response For a Class | CK 1994 | — |
| Depth Of Inheritance Tree | CK 1994 | — |
| Number Of Children | CK 1994 | — |
| Class Length | — | — |

### File level (Kotlin)

| Metric | Default warning |
| --- | --- |
| Top-Level Declarations Per File | 10 |
| Types Per File | — |

### Package level (Martin 1994)

All measure-only / informational; values rank change-impact rather than fire as verdicts.

| Metric | Default warning |
| --- | --- |
| Efferent Coupling (Ce) | — |
| Afferent Coupling (Ca) | — |
| Instability (I) | — |
| Abstractness (A) | — |
| Distance From Main Sequence (D) | — |

## Configuration

Minimal `ktrics.yaml` at the project root:

```yaml
# yaml-language-server: $schema=https://raw.githubusercontent.com/koji-1009/ktrics/main/schemas/ktrics-config.schema.json

ktrics:
  metrics:
    cyclomatic-complexity:
      warning: 10
      error: 20
    cognitive-complexity:
      warning: 15
  exclude:
    - "**/build/**"
```

Multi-module projects declare the module graph under `modules: { declared: [...] }` (or per-invocation via `--module`); v2 will auto-derive it from the Gradle/Maven build. The `# yaml-language-server` directive turns on autocomplete + typo detection in editors with [yaml-language-server](https://github.com/redhat-developer/yaml-language-server) integration. Every key is documented in [`schemas/ktrics-config.schema.json`](schemas/ktrics-config.schema.json) and explained in `ktrics manual`. This repository's own [`ktrics.yaml`](ktrics.yaml) — ktrics dogfoods itself — is a full multi-module example.

## Output formats

`--reporter` accepts `console` (default), `json`, `md` (PR comments / issue bodies), `ai` (token-efficient bundle starting with `# ktrics ai-report v1`), and `sarif` (SARIF 2.1.0 for GitHub Code Scanning / GitLab).

## Documentation

- [`ktrics ai-loop`](doc/ai-loop.md) — operational playbook: shell commands, prompt examples, dismiss syntax, four-station walkthrough of one full refactor iteration. Start here when you want to run ktrics.
- [`ktrics manual`](doc/manual.md) — conceptual reference: lens design, every flag, dismissal mechanics, refactor / dismiss decision tree, resolution model, exit codes. Come back here when you need to know *why* a lens fires.
- [`doc/calibration.md`](doc/calibration.md) — citation audit, selection principles, counting-rule deviations.
- [`schemas/`](schemas/) — JSON Schema files: `ktrics-config.schema.json` for `ktrics.yaml`, `ktrics-dismissals.schema.json` for the dismissals sidecar.

## Limitations

- **Resolution is on by default within the project; external edges need the classpath.** A reference into a dependency not on the classpath degrades *that single edge* to name-based, flagged via the `resolution` field. In-project and cross-language (Kotlin↔Java) edges resolve without a build.
- **ktrics builds on the Kotlin Analysis API Standalone**, which is version-locked to its Kotlin release and not yet a fully supported standalone use case upstream. The version is pinned and upgrades are gated by a cross-language resolution test corpus in CI. This cost is deliberate — it is what makes the analysis [resolution-backed](#resolution-backed-by-design).
- **Multi-module is first-class; module discovery is staged.** v1 requires the graph to be *declared* (`ktrics.yaml` / `--module`); v2 auto-derives it via Gradle/Maven. Kotlin Multiplatform source sets and `expect`/`actual` are a v1 limitation — model the JVM source sets.
- **Daemon mode assumes process + filesystem persist across the loop.** Fully ephemeral container-per-call CI loses warmth; use CRaC (Linux) or accept one cold platform start.
- **The native-image client links none of the platform** — any path needing analysis goes through the daemon, never the client.
- Report field names are stable through `0.x`, not yet externally stress-tested — pin a version in CI.
- The built-in metric set is curated, not exhaustive; Halstead / MI are off by default. See [`doc/calibration.md`](doc/calibration.md) for the selection principles.

## Development

```bash
./gradlew build                    # all modules
./gradlew :client:nativeCompile    # GraalVM native-image client
./gradlew :daemon:installDist      # self-contained daemon
./gradlew test                     # golden + unit tests
```

Requires JDK 21 (GraalVM for `nativeCompile`). The Kotlin Analysis API Standalone + IntelliJ platform resolve from the JetBrains repositories (pinned in `gradle/libs.versions.toml`).

## Related projects

The same metrics-as-lenses design for other ecosystems: [`dartrics`](https://github.com/koji-1009/dartrics) (Dart) and [`cargo-rustics`](https://github.com/koji-1009/cargo-rustics) (Rust).

## License

MIT.
