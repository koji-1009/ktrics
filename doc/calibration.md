# ktrics — Calibration & Documented Deviations

Every place ktrics deviates from a cited paper, or makes a deterministic approximation, is recorded here. Thresholds are starting points; calibrate per project.

## Default gating policy (calibrated by dogfooding)

ktrics gates on **function-level** lenses only; **class- and package-level** lenses are measure-only by default (no warning until you set one in `ktrics.yaml`). This mirrors the sibling tools' `—`/`neutral` defaults for the CK and Martin suites. Rationale, confirmed by running ktrics on its own source: project-wide structural thresholds (LCOM4, CBO, RFC, WMC, NOM, DIT, NOC, class-length, and the Martin distance D) fire on many idiomatic shapes and drown the genuinely actionable function-level findings. With class/package measure-only, analyzing the `metric` module drops from 18 violations (mostly LCOM4 on interfaces and Martin-D on leaf packages) to 4 — all real complexity hotspots.

- **LCOM4 is N/A on interfaces and annotations.** They have no implementation, so every abstract method is its own component and LCOM4 just counts methods — pure noise. The calculator returns no value for `interface`/`annotation` kinds.
- **Martin distance-from-main-sequence (D) is informational.** A leaf/utility package with no in/out package edges has `Ce = Ca = 0`, forcing `D = 1`; gating on that flags every such package. D ranks change-impact drift instead.
- **Build scripts are never analyzed.** `*.gradle.kts` (and `settings.gradle.kts`) are tooling config with no package, not the codebase under review; the file collector skips them so they don't emit empty-scope package violations.

## Cyclomatic complexity (McCabe 1976)

`1 + decisionWeight` summed over the body. Decision points: `if`, `for`/`foreach`, `while`, `do`, each non-else `case`/`when` entry, `catch`, ternary/`?:`/elvis, and each `&&`/`||`. Java groups chained operators into one polyadic node, so `decisionWeight` returns the operator count there (a `a && b && c` contributes 2), matching Kotlin where each `&&` is its own node. Decisions inside nested lambdas are counted — in Kotlin, inline lambdas (`forEach`/`let` bodies) *are* the enclosing function's control flow, so the fold-in is deliberate (the sibling tool excludes closures; this is a per-language judgment, not an oversight).

**Sealed dispatch discount (resolved mode):** a `when` whose subject resolves to a `sealed` class contributes no entry decisions — the compiler enforces exhaustiveness, so the arms are an enumeration, not a "did I forget one?" load (ports the sibling tool's sealed-switch rule). In name-based runs the subject cannot be resolved and entries count normally — the documented per-edge degrade.

## Cognitive complexity (SonarSource 2018)

B1 increment per control structure + B2 nesting penalty (`+depth`) + B3 one increment per run of like logical operators, per the spec's branch rules: `else if` and `else` charge +1 flat (the chain head pays the only nesting price), a labeled `break`/`continue` charges +1, and lambdas/local functions raise the nesting level **without** an increment of their own. `?.` alone adds nothing. **Deviation:** direct recursion is not yet counted; nested-lambda *content* still accrues to the enclosing function (nested callables are not extracted as separate scopes — see the cyclomatic note above).

**Test-DSL discount (`test: true`):** on conventional test files, closures passed as call arguments (`test("…") { … }` registration callbacks) are skipped entirely — neither their contents nor their nesting accrue to the enclosing function, which otherwise fires purely on the closure-nesting tax of declarative suites. The function's own control flow and named helpers stay scored.

## Maximum nesting level

Control-structure depth only (`if`/loops/`when`/`catch`), with `else if` links sharing the chain head's level. **Lambdas deliberately do not count** — unlike cognitive's B2 — because counting them fires the lens on flat, idiomatic builder DSLs (`buildJsonArray { forEach { add(…) } }` reads linearly but stacks three closures). Confirmed by dogfooding: with lambdas counted, ktrics' own reporters lit up at "nesting 6–7" on code with no nested control flow at all.

## NPATH (Nejmeh 1988)

Computed as the product of per-branch multipliers over the body (binary branches ×2; multi-way `switch`/`when` ×entries; loops ×2), capped at 1e9 to avoid overflow. **Deviation:** this is a deterministic approximation of the full recursive formula, which adds (rather than multiplies) for else-less `if`s; the product form is monotonic and well-defined. **Measure-only by default** (calibrated by dogfooding): the product form multiplies every independent guard clause even though an early-return dispatcher only ever takes one path, so a flat guard chain reads as thousands of "paths". Cyclomatic + cognitive carry the function-level branch-complexity gate (this also matches the sibling tool, which omits npath); set `metrics: { npath-complexity: { warning: N } }` to enforce it.

## SLOC (Boehm 1981)

Non-blank, non-comment-only physical lines, via a block-comment state machine. **Deviation:** a `//` or `/*` inside a string literal is treated as a comment start; this rarely changes a count and keeps the counter language-neutral.

## Halstead (1977) / Maintainability Index (Oman 1992)

Operator/operand classification: identifiers and literals are operands, everything else operators. MI uses the SEI-normalised form clamped to [0, 100]. Both off by default — composite and contested.

## DIT / NOC (CK 1994)

DIT counts ancestors up to (not including) the implicit root (`Object`/`Any`); a type with no explicit supertype has DIT 0. Until resolution is enabled, supertype names are resolved by matching against same-package types and imports; an unresolved (external) supertype counts the edge but stops the walk, and the result is stamped `name-based`.

## Call-graph signals & `inspect` (reference-only, resolution-backed)

The `signals:` block and the `inspect` command are built on the **resolved** symbol space, not simple names: each call/type reference becomes its fully-qualified, owner-disambiguated key (`com.example.Foo.bar`), and an edge connects only when that key matches a project-local declaration's key. So `A.run` and `B.run` do **not** conflate, and a resolved reference to a library symbol (`kotlin.run`) is recognised as off-project and produces no edge rather than over-linking to every project `.run`. `ResolvedCallGraphTest` proves this in a live standalone session; `inspect` also tolerates Kotlin's synthetic `Companion` segment so `Foo.bar` matches `Foo.Companion.bar`.

A single edge degrades to **name-based** (simple-name match) only when that reference genuinely cannot be resolved — i.e. its target's definition is not on the session's classpath. The Kotlin stdlib is always supplied (so `run`/`let`/`map`/… resolve), and in-project + cross-module + cross-language edges resolve from source without a build; the residual is a project's own third-party dependencies when their jars aren't declared in the module graph. That residual is **not avoidable by any static tool**: you cannot resolve a symbol whose definition you were never given. It is the exact `external edges need the classpath` constraint from the README — declare the module classpath and the edge resolves. These values are still *reference information* ("compare against intent"), never a gate.

## `--concurrency` (bounded by the embedded platform, not a free knob)

Accepted for CLI parity but currently **advisory**, and this is a genuine constraint of the embedded analyzer, not a missing feature. ktrics' core design is **one** K2/IntelliJ platform host holding **one** shared symbol space so Kotlin↔Java and cross-module references resolve in every direction. That platform's resolution runs under the IntelliJ read-action model, which does not permit concurrent analysis on a single project; the only way to use N cores would be N independent sessions, and independent sessions cannot share a symbol space — so the cross-everything resolution that is the tool's reason for existing is fundamentally in tension with fanning *analysis* across threads. The flag is parsed (so it is never mis-read as a positional) and reserved for parallelising the embarrassingly-parallel, resolution-free stages (file collection, JaCoCo/LCOV parsing) and for a future multi-host sharding mode that would trade some cross-shard resolution for cores.

## Martin package metrics (1994)

Efferent coupling counts other packages a package depends on (via imports); the package of an import is derived by the heuristic "a final segment starting uppercase is a type name". Afferent coupling counts in-project packages depending on this one. Instability `I = Ce/(Ca+Ce)`; abstractness `A`; distance `D = |A + I − 1|`.
