# ktrics — AI Loop Playbook

**Agents: start here.** This is the operational playbook — the shell commands you actually run, how to wire them into your agent, the dismiss syntax, and one full refactor iteration end to end. For the *why* behind each lens, read `ktrics manual`.

The loop is four stations: **analyze → fix → regression → confirm.** ktrics is built so that loop feels native-fast (warm daemon) and so you never need a second call to understand a finding (auto-explain is inline).

## The one command you run first

```bash
ktrics analyze . --reporter ai
```

Output begins with the contractual header `# ktrics ai-report v1`, then a `counts:` block naming each section's entry total (`violations` / `unused` / `staleDismissals` / `signals`). **Read totals from `counts:`** — all four sections share the `- file:` entry shape, so grepping to count findings over-counts. With `--limit`, dropped tails land in a trailing `truncated:` block (section total = counts + drops). Violations are sorted by **actionability** (errors first, then by how far past the threshold). Every violation carries, inline:

- `id` — stable across runs (`sha256("file|scope|metric")`, 16 hex). The same finding keeps the same id even as line numbers move, so you can tell whether your fix actually landed.
- `metric`, `severity`, `value`, `threshold`, `polarity`.
- `language: java|kotlin` — one run spans both.
- `resolution: resolved|name-based` — confidence of coupling metrics. `name-based` means an edge could not be resolved (a dependency off the classpath); treat its count as approximate.
- `rationale`, `refactorHints`, `references` — auto-explain. The *why*, concrete moves, and the primary source, inline.
- `snippet` — the offending line ± 3.
- `complexityJustified: true` — (with `--coverage`) the complex method has ≥ 0.8 branch coverage; consider accepting rather than refactoring.

After the violations, the same report carries three **reference-only** blocks (no severity, never a gate):

- `unused:` — unreachable public-API declarations, each with its own `snippet`. Read each **as a question, not a verdict**: a 0-reachability reading can be genuine dead code OR an *unwired implementation* (the caller integration never landed) OR a reflective/generated consumer the static graph can't see. Confirm against intent before deleting.
- `signals:` — per-declaration `fanInCallers` / `fanInCalls` / `fanOutCallees` / `fanOutCalls` from the call graph, for the scopes that fired. A high fan-in is not "bad"; a 0 fan-in on a public API is a possible wiring gap. Feed it into the refactor / dismiss decision — don't treat it as a finding. `--limit` truncates the `0/0` tail and stamps a `truncated:` block.
- `staleDismissals:` — dismissal directives whose violation no longer fires (fixed, renamed, below threshold). The suppression is dead: remove the directive so it can't mask a future regression.

`--since <ref>` keeps the violations **scope-granular**: only declarations a diff hunk actually touched re-surface — an untouched sibling in a changed file stays out, and a pure rename surfaces nothing. (`unused:` stays file-granular: reachability is relational, so a change elsewhere can legitimately flip it.)

By default ktrics **gates on function-level lenses** (cyclomatic, cognitive, nesting, parameters, boolean-trap, SLOC, Kotlin `!!`/scope-fn; npath is measure-only — its product-form estimate over-counts guard clauses). Class- and package-level lenses (CK suite, Martin) are **measure-only** — they surface in `json`/`md` and via `--reporter ai` only when you set a threshold in `ktrics.yaml`. This keeps the failing set tight and actionable.

## Wire it into your agent

`ktrics analyze --reporter ai` is a token-efficient bundle made to pipe straight into a coding agent:

```bash
# Claude Code
ktrics analyze . --reporter ai | claude -p "Refactor the threshold violations; keep behaviour identical."

# Codex CLI / Aider / OpenHands / Cursor (stdin or a pasted block)
ktrics analyze . --reporter ai > /tmp/ktrics.txt   # then attach /tmp/ktrics.txt to the agent

# Only what changed since the last commit (tight loop)
ktrics analyze . --reporter ai --since HEAD

# Cap the agent's working set to the top N
ktrics analyze . --reporter ai --limit 10
```

A good driving prompt: *"Here is a ktrics ai-report. For each violation, either (a) refactor to bring the metric under threshold without changing behaviour, or (b) if the complexity is essential, add a `// ktrics:dismiss <metric> reason=\"…\"` comment directly above the declaration explaining why. Then stop — I will re-run ktrics."*

## Station 2 — fix, keyed by stable id

Apply a refactor. Re-run `ktrics analyze . --reporter ai`. Compare ids:

- a violation whose `id` **persists** → your fix did not take (the metric is still over threshold);
- a vanished `id` → it landed;
- a new `id` → the refactor introduced a new hotspot (e.g. you split one big method into helpers but one helper is still complex).

## Station 3 — regression between two refs

```bash
ktrics regression --before HEAD~1 --after HEAD --reporter ai
```

Per-scope per-metric deltas, classified `improved` / `regressed` / `unchanged` / `neutralDelta` / `added` / `removed` by polarity (`neutralDelta` = an informational metric that moved; there is no better/worse verdict to attach). `cosmeticSplitDetected` flags churn that merely shuffles code without reducing complexity (many tiny helpers added, SLOC up, complexity barely down) — so an agent can't "win" by extracting noise. It is a **narrow heuristic, not a global verdict**: `false` only means that one signature did not match. `--reporter ai|json|console` picks the shape; `--metric <ids>` restricts the diff to specific metrics; `--output <file>` writes it. Both refs are analyzed on the project's own subtree — a project rooted in a repo subdirectory diffs the matching subdirectory at each ref, under that subtree's `ktrics.yaml`.

## Station 4 — confirm, then accept or dismiss

For findings you accept in place, put a directive on the line **directly above** the declaration (a blank line between invalidates it):

```kotlin
// ktrics:dismiss cyclomatic-complexity reason="hand-rolled state machine; splitting hurts readability"
fun lineHasCode(...) { ... }
```

```java
// ktrics:dismiss cyclomatic-complexity reason="exhaustive switch over the AST node kinds"
int classify(PsiElement n) { ... }
```

Same syntax for both languages. A YAML sidecar `ktrics-dismissals.yaml` is also honored and **wins on collision** (useful for bulk or id-keyed dismissals). A reason shorter than `minReasonLength` keeps the violation **live** with a `dismissalRejected` flag — so a drive-by `reason="wontfix"` doesn't silence anything. `--strict-dismiss` ignores every dismissal (for a clean-slate audit).

When a later run no longer fires the violation a directive suppressed, the directive surfaces under `staleDismissals:` (and a stderr WARN) — delete it as part of the same iteration, exactly like a vanished violation id confirms a fix.

## The unused-detector loop

`ktrics analyze` already lists unreachable public declarations in the `unused:` block. `ktrics unused` is the focused subcommand that emits only that block (no metric battery, no signals) — reach for it when you want a dead-code sweep:

```bash
ktrics unused --reporter ai            # console is the default; ai is the YAML block
ktrics unused --filter method,class    # narrow by declaration kind
ktrics unused --include-tests          # widen into test trees (excluded by default)
```

Each entry is **a question, not a verdict** (leftover code vs. unwired implementation vs. reflective consumer). Before deleting, confirm there's no inbound edge:

```bash
ktrics inspect <symbol> --direction up --depth 3
```

Empty upstream → the detector saw it correctly, safe to delete. Non-empty upstream → the detector missed an indirection; do **not** `--apply`. Then:

```bash
ktrics unused --apply
```

In-place deletion of unused top-level declarations (git-recoverable, never destroys files). Two safety gates: it refuses when reachability was **name-based** (an incomplete classpath would risk deleting live code — make resolution complete first), and it refuses on a **dirty git tree** so the deletion lands in its own diff (override with `--force` only if you've accepted that trade-off).

## When the metric alone isn't enough — `ktrics inspect`

The ai-report's `signals:` block carries per-declaration fan-in / fan-out for the scopes the metrics fire on. When a violation reads ambiguously — *should I refactor this hub, or is it correctly central?* — drill in:

```bash
ktrics inspect Parser.parse --direction up --depth 2
```

A YAML subgraph: the matched anchor with its fan-in / fan-out, then upstream callers (`--direction up`) and/or downstream callees (`--direction down`) up to `--depth` edges away (`--reporter json` for the machine shape). Three entry points:

- **Disambiguating an unused report** — `--direction up --depth 3` before deleting confirms no inbound edge exists.
- **Sizing a refactor's blast radius** — `--direction up --depth 2` enumerates the call sites a signature change would have to follow.
- **Reading a coordinator** — `--direction down --depth 2` on a high-`fanOutCallees` scope shows whether `response-for-class` is over-firing (callees are siblings of one protocol) or correctly firing (callees span unrelated subsystems).

Inspect is **not part of the refactor / dismiss / punt decision** — it feeds that decision with structure the metric value alone doesn't carry. The call graph is **resolution-backed** (edges match on fully-qualified, owner-disambiguated keys, so `A.run` ≠ `B.run`); a single edge falls back to a name only when its target can't be resolved (a dependency off the classpath — declare the module classpath to resolve it). It is reference information either way — "compare against intent," not a verdict. There are no `md` / `sarif` reporters because there's no finding to render.

## Keeping it fast

The daemon (`ktricsd`) auto-spawns on the first call and stays warm, holding the module-aware cross-file index + snapshot in memory; invalidation is dependency-aware along the module graph. `--since <ref>` and `--snapshot baseline` diff incrementally with no cold start. In container-per-call CI the daemon can't survive between calls — use CRaC restore (Linux) or accept one cold platform start per invocation; invoking `ktricsd <command>` directly runs a single foreground one-shot with no warm daemon. The snapshot cache is keyed by (project root + module graph + classpath + config hash), so differing invocations never share an index.

## Cross-language note

Kotlin↔Java references resolve **both directions** from source — the `resolution` field tells you when an edge fell back to name-based (a dependency missing from the classpath). For a mixed module whose Java you want fully resolved, declare its module graph in `ktrics.yaml` so the classpath is complete.
