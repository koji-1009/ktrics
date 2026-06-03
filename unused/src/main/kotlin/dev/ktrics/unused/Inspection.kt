package dev.ktrics.unused

import dev.ktrics.ir.CallGraphSignal

/** Which side(s) of the call graph `ktrics inspect` walks from the anchor (sibling of dartrics). */
enum class InspectionDirection { UP, DOWN, BOTH }

/** A neighbour node, annotated with graph distance back to the anchor and the per-hop edge weight. */
data class InspectionNode(
    val signal: CallGraphSignal,
    /** Edges separating this node from the anchor (1 = direct caller/callee, 2 = one hop further). */
    val depth: Int,
    /**
     * Edge weight on the hop *into* this node from the closer side of the chain. Upstream: how many
     * times this caller invokes the next-closer node toward the anchor. Downstream: how many times the
     * next-closer node invokes this one.
     */
    val incomingEdgeCount: Int,
)

/** One anchor declaration the inspector matched, with its upstream/downstream subgraphs. */
data class InspectionMatch(
    val anchor: CallGraphSignal,
    /** Callers, ordered by depth ascending then fanInCallers descending. */
    val upstream: List<InspectionNode>,
    /** Callees, ordered by depth ascending then fanOutCallees descending. */
    val downstream: List<InspectionNode>,
)

/** Result envelope returned by `ktrics inspect`, echoing the query so JSON consumers keep the scope. */
data class InspectionResult(
    val query: String,
    val depth: Int,
    val direction: InspectionDirection,
    val matches: List<InspectionMatch>,
)
