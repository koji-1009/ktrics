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

`--config`, `--reporter (console|json|md|ai|sarif)`, `--output`, `--root`, `--module <name=srcRoots[:deps]>`, `--since <ref>`, `--no-auto-explain`, `--snapshot (cache|baseline|none|<path>)`, `--coverage <path|none>`, `--strict-dismiss`, `--limit <n>`, `--fatal-warnings`.

Command-specific: `inspect` takes `--depth <n>` (default 2) and `--direction up|down|both` (default both), reporters `ai|json`. `unused` takes `--filter <kinds>` (e.g. `method,class`), `--include-tests`, and `--apply [--force]`. `regression` takes `--reporter ai|json|console`, `--output`, and `--metric <ids>`. `--concurrency <n>` is accepted for parity but advisory under the warm single-host daemon (analysis runs on one platform host).

## Exit codes (sysexits)

`0` clean · `1` violations with `--fatal-warnings` · `64` usage · `65` bad input / unresolved git ref · `70` internal · `78` bad config.

## Resolution

Resolution is **on by default within the project**; external edges need the classpath. A reference into a dependency that isn't on the classpath degrades *that single edge* to name-based, flagged via the `resolution` field on every coupling/cohesion result. In-project and cross-language (Kotlin↔Java) edges resolve without a build.

Resolution is what the analysis stands on — it is why the embedded platform is worth its weight. With it, coupling/cohesion/inheritance lenses count the declarations a reference *actually* targets (not same-name guesses), `unused` is a reachability sweep over the resolved reference graph spanning modules and the Kotlin↔Java boundary, and `inspect` walks a resolved call graph. Without it, every one of those answers degrades to name matching — which is why the degradation is stamped per measurement instead of hidden, and why destructive operations (`unused --apply`) require a fully RESOLVED sweep.

## Loop modes

- **Persistent mode (default; local agents):** the daemon stays warm across tool calls → near-native iteration. This is the happy path.
- **Ephemeral mode (container-per-call CI):** the daemon can't survive between calls. Use CRaC restore (Linux) or accept one cold platform start per invocation. There is no native-only fast path — the platform is required for any analysis.
