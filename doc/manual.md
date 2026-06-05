# ktrics — Operator's Manual

`ktrics` is a code-quality metrics tool for **Java and Kotlin** — the AI-loop counterpart of `dart analyze` / `cargo clippy`. It **measures, it does not gate**: each metric is an independent *lens* anchored to a primary source. Accept / refactor / dismiss stays in your loop; no metric blocks another.

## Architecture in one paragraph

A tiny native-image client (`ktrics`) relays your command to a warm JVM daemon (`ktricsd`) over a local socket. The daemon embeds the K2/JetBrains analysis platform — the Kotlin Analysis API plus the platform's Java PSI — as **one host for both languages**, sharing a single symbol space so Kotlin↔Java references resolve both directions. The client links none of the platform, so it starts in milliseconds; the daemon stays warm so every loop iteration is sub-second.

## Commands

| command | purpose |
| --- | --- |
| `ktrics analyze <path>` | run every enabled lens + the `unused:` and `signals:` blocks |
| `ktrics unused [--apply]` | public-API reachability; `--apply` deletes top-level orphans (safety-gated) |
| `ktrics inspect <symbol>` | walk the call graph around a declaration (`--depth`, `--direction up\|down\|both`) |
| `ktrics regression --before <ref> --after <ref>` | per-scope per-metric diff classified by polarity |
| `ktrics report <input.json>` | re-emit a saved JSON report in another format |
| `ktrics rules` | the full metric catalogue |
| `ktrics explain <metric-id>` | full rationale, refactor hints and references for one metric |
| `ktrics manual` | this manual |
| `ktrics ai-loop` | the loop walkthrough |
| `ktrics doctor` | validate `ktrics.yaml` |
| `ktrics daemon status\|stop` | daemon lifecycle (auto-spawns on demand) |
| `ktrics --version` | print version |

## Common flags

`--config`, `--reporter (console|json|md|ai|sarif)`, `--output`, `--root`, `--module <name=srcRoots[:deps]>`, `--since <ref>`, `--no-auto-explain`, `--snapshot (cache|baseline|none|<path>)`, `--coverage <paths|none>`, `--strict-dismiss`, `--limit <n>`, `--fatal-warnings`.

`--since <ref>` is **scope-granular for violations**: a violation surfaces only when a `git diff` hunk (vs the working tree) intersects its declaration's span — an untouched sibling in a changed file stays filtered, and a pure rename surfaces nothing. The `unused:` block stays file-granular, because reachability is call-graph-relational: a change elsewhere can legitimately flip it on an untouched declaration. `--coverage` takes one JaCoCo XML path or a comma-separated list (multi-module builds emit one report per module; the list is merged). `--snapshot baseline` advances the baseline every run — diff against the immediately-prior run; use `cache` or a fixed `<path>` you don't overwrite for a stable reference.

Command-specific: `inspect` takes `--depth <n>` (default 2) and `--direction up|down|both` (default both), reporters `ai|json`. `unused` takes `--filter <kinds>` (e.g. `method,class`), `--include-tests`, and `--apply [--force]`. `regression` takes `--reporter ai|json|console`, `--output`, and `--metric <ids>`. `--concurrency <n>` is accepted for parity but advisory under the warm single-host daemon (analysis runs on one platform host).

## Exit codes (sysexits)

`0` clean · `1` violations with `--fatal-warnings` · `64` usage · `65` bad input / unresolved git ref · `70` internal · `78` bad config.

## Resolution

Resolution is **on by default within the project**; external edges need the classpath. A reference into a dependency that isn't on the classpath degrades *that single edge* to name-based, flagged via the `resolution` field on every coupling/cohesion result. In-project and cross-language (Kotlin↔Java) edges resolve without a build.

Resolution is what the analysis stands on — it is why the embedded platform is worth its weight. With it, coupling/cohesion/inheritance lenses count the declarations a reference *actually* targets (not same-name guesses), `unused` is a reachability sweep over the resolved reference graph spanning modules and the Kotlin↔Java boundary, and `inspect` walks a resolved call graph. Without it, every one of those answers degrades to name matching — which is why the degradation is stamped per measurement instead of hidden, and why destructive operations (`unused --apply`) require a fully RESOLVED sweep.

## Unused detection — entry points and presets

Roots are `main`, `@Test`/`@ParameterizedTest`, anything in `unused: { entry-points: [...] }`, every `override`, and the keep-alive surface from presets. Presets expand to framework annotations whose carriers are reached reflectively (`lombok`, `jpa`, `jackson`, `spring`, `kotlinx-serialization`, `room`, `compose`, `parcelize`, `moshi`, `dagger`, `android`, `ktor`); a kept TYPE covers its members too.

**Presets are auto-detected from imports** (on by default): code importing `androidx.*` IS an Android app, `org.springframework.*` a Spring app, `io.ktor.*` a Ktor app, and so on for every preset — so an unconfigured project gets the right keep-alive surface and ONLY that surface (a Spring backend never carries android's `Service` supertype rule). `unused: { presets: [...] }` adds presets on top; `unused: { auto-presets: false }` turns detection off.

The `android` preset additionally keeps types by SUPERTYPE suffix (`Activity`, `Fragment`, `Service`, `BroadcastReceiver`, `ContentProvider`, `Application`, `ViewModel`, `View`, `Worker`) — Android components are manifest-wired through inheritance, not annotations. Suffix matching is case-sensitive (CamelCase bounds it: `Activity` covers `AppCompatActivity` and your own `BaseActivity` chain transitively; `View` does not match `Preview`); in resolved mode a suffix only applies to inheritance that leaves the project (an in-project `UserService : CrudService` is governed by the transitive closure, not the suffix), and over-keeping remains the deliberate, safe direction. Note the inherent blind spot: ktrics does not read the manifest, so a component class removed from the manifest but still extending `Activity` will not be reported. Ktor note: `@Resource` routes are kept, but EngineMain-style modules declared in `application.conf` (`fun Application.module()`) are reached by reflection from config and are not statically expressible — reference the module from a `main`, or dismiss it.

## Dismissals

Two channels: a `// ktrics:dismiss <metric> reason="…"` comment directly above the declaration (metric optional — omit to dismiss every lens on it), and the `ktrics-dismissals.yaml` sidecar (selectors: stable `id`, or `metric`+`scope`, or `metric`+`file`; the sidecar wins on collision). A reason shorter than `minReasonLength` keeps the violation live, flagged `dismissalRejected`. The sidecar accepts an optional `version: 1` (a different version makes ktrics ignore the file loudly — never half-apply a future format); `ktrics doctor` validates it.

**Stale dismissals are reported, not silently dropped:** a directive whose violation no longer fires (fixed, renamed, below threshold) surfaces on stderr and in the report's `staleDismissals:` block — remove it. `--strict-dismiss` ignores every dismissal and reports nothing as stale.

## Loop modes

- **Persistent mode (default; local agents):** the daemon stays warm across tool calls → near-native iteration. This is the happy path.
- **Ephemeral mode (container-per-call CI):** the daemon can't survive between calls. Use CRaC restore (Linux) or accept one cold platform start per invocation. There is no native-only fast path — the platform is required for any analysis.
