package dev.ktrics.unused

import dev.ktrics.ir.CallGraphSignal
import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl
import dev.ktrics.langapi.NodeClassifier

/**
 * The project call graph (sibling of dartrics' resolved reachability/inspect). Nodes are project-local
 * declarations; edges follow simple-name references between them. It produces per-declaration
 * [CallGraphSignal]s — reference-only fan-in/fan-out totals, NOT findings — and a depth-bounded
 * neighbourhood walk for `ktrics inspect`. References that don't resolve to a project-local
 * declaration (SDK, off-project dependency) are excluded as both source and target, matching the
 * scoping the unused-reachability sweep uses.
 */
class CallGraph private constructor(private val nodes: List<Node>) {
    private data class Node(
        /**
         * Param-including identity that distinguishes overloads sharing one human [key] (e.g.
         * `pkg.C.foo(kotlin.Int)` vs `pkg.C.foo(kotlin.String)`). All per-node maps (signals,
         * fan-in/out, adjacency) are keyed by this so no overload is dropped; a no-arg `foo` is
         * `pkg.C.foo()`. Edges still resolve by NAME — see [byKey]/[bySimple].
         */
        val identity: String,
        /** Human-readable, param-less scope name, used for display and `inspect`/`signalOf` queries. */
        val key: String,
        val simpleName: String,
        val kind: String,
        val file: String,
        val line: Int,
        val rawOut: List<String>,
    )

    // Edge resolution is by NAME (a call site carries no argument types), so both indices are
    // multimaps: a name with several overloads resolves to ALL of them (over-approximate — safe).
    private val byKey: Map<String, List<Node>> = nodes.groupBy { it.key }
    private val bySimple: Map<String, List<Node>> = nodes.groupBy { it.simpleName }

    // Distinct-target / total-call accumulators, plus per-edge weights for the inspect walk. Keyed by
    // node identity so distinct overloads keep distinct signals and fan-in/out.
    private val fanOutCalls = HashMap<String, Int>()
    private val fanInCalls = HashMap<String, Int>()
    private val fanOutCallees = HashMap<String, MutableSet<String>>()
    private val fanInCallers = HashMap<String, MutableSet<String>>()
    private val edgeWeight = HashMap<Pair<String, String>, Int>()

    private val signalByIdentity: Map<String, CallGraphSignal>

    // Precomputed adjacency so an `inspect` walk is O(neighbours), not an O(E) scan of every edge per hop.
    private val downstreamAdj: Map<String, List<Pair<String, Int>>>
    private val upstreamAdj: Map<String, List<Pair<String, Int>>>

    init {
        for (source in nodes) {
            for (rawName in source.rawOut) {
                val targets = targetsOf(rawName, source.identity)
                if (targets.isEmpty()) continue // off-project reference: excluded as a graph edge
                for (target in targets) {
                    // One outbound edge per (source → target) pair: keeps fanOutCalls ≥ fanOutCallees and
                    // reconciles with the fanInCalls it produces, even when a name resolves to homonyms.
                    fanOutCalls.merge(source.identity, 1, Int::plus)
                    fanOutCallees.getOrPut(source.identity) { HashSet() }.add(target.identity)
                    fanInCalls.merge(target.identity, 1, Int::plus)
                    fanInCallers.getOrPut(target.identity) { HashSet() }.add(source.identity)
                    edgeWeight.merge(source.identity to target.identity, 1, Int::plus)
                }
            }
        }
        signalByIdentity = nodes.associate { it.identity to signalFor(it) }
        downstreamAdj = edgeWeight.entries.groupBy({ it.key.first }) { it.key.second to it.value }
        upstreamAdj = edgeWeight.entries.groupBy({ it.key.second }) { it.key.first to it.value }
    }

    /**
     * Resolves one outgoing reference to its project-local target(s):
     * - an exact key hit resolves to every overload sharing that key (over-approximate — safe);
     * - a QUALIFIED reference (`pkg.x`) is first matched against in-project nodes by full key, then by
     *   DOTTED SUFFIX — a node whose key ENDS WITH ".$rawName" (so `Outer.Inner` → `pkg.Outer.Inner`).
     *   This catches an in-project nested type/member written `Outer.Inner` whose node key is
     *   package-qualified; adding such edges only ADDS reachability. The suffix carries the whole dotted
     *   reference, so `kotlin.run` (suffix `.kotlin.run`) still matches no project node;
     * - a qualified reference matching NOTHING in-project is off-project (SDK/dependency, e.g.
     *   `kotlin.run`) and yields NO edge — it must NOT fall back to every project `.run`;
     * - an unqualified (name-based) reference falls back to simple-name matching.
     */
    private fun targetsOf(
        rawName: String,
        sourceIdentity: String,
    ): List<Node> {
        byKey[rawName]?.let { return it.filter { n -> n.identity != sourceIdentity } }
        if ('.' in rawName) {
            // An in-project nested type/member written `Outer.Inner`: look up by the simple name (last
            // segment) via the precomputed index, then keep only candidates whose full key ends with the
            // WHOLE dotted reference. Going through bySimple avoids an O(N) scan of every node per reference
            // (which, in resolved mode where most refs are fully-qualified off-project keys that miss byKey,
            // made build() quadratic); a fully-qualified off-project ref like `kotlin.run` still matches no
            // project key, so it correctly yields no edge.
            return bySimple[simpleOf(rawName)].orEmpty()
                .filter { it.key.endsWith(".$rawName") && it.identity != sourceIdentity }
        }
        return bySimple[simpleOf(rawName)].orEmpty().filter { it.identity != sourceIdentity }
    }

    /**
     * Signal for a fully-qualified scope, or null when the scope is not a project-local declaration.
     * Looked up by the human (param-less) key; overloads share that key, so the first match is
     * returned — callers de-duplicate by scopeName.
     */
    fun signalOf(scopeName: String): CallGraphSignal? = byKey[scopeName]?.firstOrNull()?.let { signalByIdentity[it.identity] }

    /** Every project-local declaration's signal — one per node, so every overload is reported. */
    fun allSignals(): List<CallGraphSignal> = signalByIdentity.values.toList()

    /**
     * Walks the graph around every declaration whose name matches [query] (a bare `foo`, a dotted
     * `Type.method`, or a full qualified name), out to [depth] edges in the requested [direction].
     */
    fun inspect(
        query: String,
        depth: Int,
        direction: InspectionDirection,
    ): InspectionResult {
        val anchors = nodes.filter { matches(it, query) }
        val matches =
            anchors.map { anchor ->
                val up =
                    if (direction != InspectionDirection.DOWN) {
                        walk(anchor.identity, depth, forward = false)
                            .sortedWith(compareBy({ it.depth }, { -it.signal.fanInCallers }))
                    } else {
                        emptyList()
                    }
                val down =
                    if (direction != InspectionDirection.UP) {
                        walk(anchor.identity, depth, forward = true)
                            .sortedWith(compareBy({ it.depth }, { -it.signal.fanOutCallees }))
                    } else {
                        emptyList()
                    }
                InspectionMatch(signalByIdentity.getValue(anchor.identity), up, down)
            }
        return InspectionResult(query, depth, direction, matches)
    }

    private fun matches(
        node: Node,
        query: String,
    ): Boolean {
        // Tolerate Kotlin's synthetic `Companion` segment so `Foo.bar` matches `Foo.Companion.bar`.
        val normKey = node.key.replace(".Companion.", ".")
        return node.key == query || node.simpleName == query ||
            node.key.endsWith(".$query") || normKey == query || normKey.endsWith(".$query")
    }

    /**
     * BFS from [startIdentity] (a node identity, since the adjacency maps are keyed by identity);
     * forward follows callees (edge weight source→target), reverse follows callers.
     */
    private fun walk(
        startIdentity: String,
        depth: Int,
        forward: Boolean,
    ): List<InspectionNode> {
        val result = ArrayList<InspectionNode>()
        val visited = hashSetOf(startIdentity)
        var frontier = listOf(startIdentity)
        for (d in 1..depth) {
            val next = ArrayList<String>()
            for (cur in frontier) {
                for ((neighbor, weight) in neighborsOf(cur, forward)) {
                    if (visited.add(neighbor)) {
                        signalByIdentity[neighbor]?.let { result.add(InspectionNode(it, d, weight)) }
                        next.add(neighbor)
                    }
                }
            }
            if (next.isEmpty()) break
            frontier = next
        }
        return result
    }

    /** (neighbour, edgeWeight) pairs: forward → callees of [identity]; reverse → callers of [identity]. */
    private fun neighborsOf(
        identity: String,
        forward: Boolean,
    ): List<Pair<String, Int>> = (if (forward) downstreamAdj else upstreamAdj)[identity].orEmpty()

    private fun signalFor(node: Node): CallGraphSignal =
        CallGraphSignal(
            file = node.file,
            scopeName = node.key,
            kind = node.kind,
            line = node.line,
            fanInCallers = fanInCallers[node.identity]?.size ?: 0,
            fanInCalls = fanInCalls[node.identity] ?: 0,
            fanOutCallees = fanOutCallees[node.identity]?.size ?: 0,
            fanOutCalls = fanOutCalls[node.identity] ?: 0,
        )

    companion object {
        fun build(
            units: List<SourceUnit>,
            classifierFor: (Lang) -> NodeClassifier,
        ): CallGraph {
            val nodes = ArrayList<Node>()
            for (unit in units) {
                val classifier = classifierFor(unit.lang)
                unit.topLevelFns.forEach { fn ->
                    val key = "${unit.packageName}.${fn.name}"
                    nodes +=
                        node(
                            key,
                            fnIdentity(key, fn),
                            "function",
                            unit.path,
                            fn.span.startLine,
                            classifier.outgoingRefNames(fn.node),
                        )
                }
                unit.topLevelProps.forEach { p ->
                    val key = "${unit.packageName}.${p.name}"
                    nodes +=
                        node(
                            key,
                            // a property has no parameter signature; it cannot be overloaded
                            key,
                            "property",
                            unit.path,
                            p.span.startLine,
                            classifier.outgoingRefNames(p.node),
                        )
                }
                unit.types.forEach { type -> collectType(type, unit, classifier, nodes) }
            }
            return CallGraph(nodes)
        }

        private fun collectType(
            type: TypeDecl,
            unit: SourceUnit,
            classifier: NodeClassifier,
            into: MutableList<Node>,
        ) {
            val qn = type.qualifiedName ?: "${unit.packageName}.${type.name}"
            // A type is a call-graph TARGET (others reference it), not a source: its methods are
            // separate nodes that carry the outgoing edges. Collecting the whole type body here would
            // double-count every method's references and make the type spuriously "call" them.
            into += node(qn, qn, type.kind.name.lowercase(), unit.path, type.span.startLine, emptyList())
            type.methods.forEach { m ->
                val key = "$qn.${m.name}"
                into +=
                    node(
                        key,
                        fnIdentity(key, m),
                        "method",
                        unit.path,
                        m.span.startLine,
                        classifier.outgoingRefNames(m.bodyNode ?: m.node),
                    )
            }
            type.nested.forEach { collectType(it, unit, classifier, into) }
        }

        /**
         * Param-including identity that keeps distinct overloads distinct: `key(t1,t2,...)`. A no-arg
         * `foo` becomes `key()`. Edges still resolve by NAME (the human [Node.key]), so collapsing two
         * overloads here would only ever drop a node/signal — never a reachability edge.
         */
        private fun fnIdentity(
            key: String,
            fn: FunctionDecl,
        ): String = "$key(${fn.params.joinToString(",") { it.typeName }})"

        private fun node(
            key: String,
            identity: String,
            kind: String,
            file: String,
            line: Int,
            rawOut: List<String>,
        ): Node =
            Node(
                identity = identity,
                key = key,
                simpleName = key.substringAfterLast('.'),
                kind = kind,
                file = file,
                line = line,
                rawOut = rawOut,
            )

        /** Normalises a raw reference text (which may be generic or nullable) to a simple name. */
        private fun simpleOf(raw: String): String =
            raw.substringBefore('<').removeSuffix("?").substringAfterLast('.').substringAfterLast("::").trim()
    }
}
