package dev.ktrics.ir

import kotlinx.serialization.Serializable

/**
 * Per-declaration call-graph numbers derived from the reference graph (sibling of dartrics'
 * `CallGraphSignal`). Signals carry NO threshold and NO severity — they are *reference information*,
 * not violations. The `ai` reporter surfaces them in a dedicated `signals:` block so an agent reads
 * them as "compare against your own intent" rather than "fix this". Scoped to project-local
 * declarations; references into the SDK or an off-project dependency are excluded as both source and
 * target (the same scoping the unused-reachability sweep uses).
 */
@Serializable
data class CallGraphSignal(
    /** Project-relative path of the file declaring the scope. */
    val file: String,
    /** Fully-qualified scope name (e.g. `pkg.Type.method`). */
    val scopeName: String,
    /** Declaration kind: `function`, `method`, `class`, `interface`, … */
    val kind: String,
    val line: Int,
    /** Distinct declarations that reference this one — a rename/​signature-change blast-radius measure. */
    val fanInCallers: Int,
    /** Total inbound reference edges (`A → this` counted once per textual reference in A) — a hot-path measure. */
    val fanInCalls: Int,
    /** Distinct project-local declarations this scope references (dependencies, not call sites). */
    val fanOutCallees: Int,
    /** Total outbound reference edges originating from this scope. */
    val fanOutCalls: Int,
)

/** One unreachable public-API declaration, as surfaced in the analyze report's `unused:` block. */
@Serializable
data class UnusedEntry(
    val file: String,
    val line: Int,
    val kind: String,
    val name: String,
)
