package dev.lab.auditor.domain

import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.round

@Serializable
enum class CycleGranularity {
    MODULE,
    PACKAGE,
}

@Serializable
data class DependencyCycle(
    val id: String,
    val granularity: CycleGranularity,
    val nodes: List<String>,
)

@Serializable
data class CouplingMetrics(
    val module: String,
    val afferentCoupling: Int,
    val efferentCoupling: Int,
    val instability: Double,
    val fanInModules: List<String>,
    val fanOutModules: List<String>,
)

@Serializable
data class GraphAnalysisSummary(
    val cycles: List<DependencyCycle>,
    val packageCycles: List<DependencyCycle>,
    val couplingMetrics: List<CouplingMetrics>,
    val fanInHotspots: List<String>,
    val fanOutHotspots: List<String>,
)

object GraphAlgorithms {

    /**
     * Iterative Tarjan's Strongly Connected Components (SCC) algorithm in O(V + E).
     * Detects both multi-node cycles (|SCC| > 1) and explicit self-loops (v -> v).
     * Vertices and edges are visited in deterministic sorted order and each cycle is
     * canonically ordered starting from its lexicographically smallest node.
     */
    fun findCycles(
        adjacency: Map<String, Set<String>>,
        granularity: CycleGranularity = CycleGranularity.MODULE,
    ): List<DependencyCycle> {
        val vertices = (adjacency.keys + adjacency.values.flatten()).toSortedSet()
        val sortedAdj = vertices.associateWith { v -> (adjacency[v] ?: emptySet()).sorted() }

        var indexCounter = 0
        val index = HashMap<String, Int>(vertices.size)
        val lowlink = HashMap<String, Int>(vertices.size)
        val onStack = HashSet<String>(vertices.size)
        val stack = ArrayDeque<String>()
        val rawSccs = mutableListOf<List<String>>()

        data class Frame(val v: String, val neighbors: List<String>, var nextIdx: Int)

        val callStack = ArrayDeque<Frame>()

        for (start in vertices) {
            if (start in index) continue
            index[start] = indexCounter
            lowlink[start] = indexCounter
            indexCounter++
            stack.addLast(start)
            onStack.add(start)
            callStack.addLast(Frame(start, sortedAdj[start].orEmpty(), 0))

            while (callStack.isNotEmpty()) {
                val frame = callStack.last()
                val v = frame.v
                if (frame.nextIdx < frame.neighbors.size) {
                    val w = frame.neighbors[frame.nextIdx]
                    frame.nextIdx++
                    if (w !in index) {
                        index[w] = indexCounter
                        lowlink[w] = indexCounter
                        indexCounter++
                        stack.addLast(w)
                        onStack.add(w)
                        callStack.addLast(Frame(w, sortedAdj[w].orEmpty(), 0))
                    } else if (w in onStack) {
                        lowlink[v] = min(lowlink.getValue(v), index.getValue(w))
                    }
                } else {
                    callStack.removeLast()
                    if (callStack.isNotEmpty()) {
                        val parent = callStack.last().v
                        lowlink[parent] = min(lowlink.getValue(parent), lowlink.getValue(v))
                    }
                    if (lowlink.getValue(v) == index.getValue(v)) {
                        val component = mutableListOf<String>()
                        while (true) {
                            val w = stack.removeLast()
                            onStack.remove(w)
                            component.add(w)
                            if (w == v) break
                        }
                        if (component.size > 1) {
                            rawSccs.add(canonicalizeCycleOrder(component, sortedAdj))
                        } else {
                            val sole = component.single()
                            if (sole in sortedAdj[sole].orEmpty()) {
                                rawSccs.add(listOf(sole))
                            }
                        }
                    }
                }
            }
        }

        return rawSccs
            .map { orderedNodes ->
                val cycleId = "cycle-${granularity.name.lowercase()}-${
                    sha256Hex("${granularity.name}:${orderedNodes.joinToString("->")}").take(12)
                }"
                DependencyCycle(
                    id = cycleId,
                    granularity = granularity,
                    nodes = orderedNodes,
                )
            }
            .sortedWith(compareBy<DependencyCycle> { it.nodes.first() }.thenBy { it.nodes.size }.thenBy { it.id })
    }

    private fun canonicalizeCycleOrder(
        component: List<String>,
        sortedAdj: Map<String, List<String>>,
    ): List<String> {
        val memberSet = component.toSet()
        val start = component.min()
        val ordered = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current: String? = start
        while (current != null && current !in visited) {
            visited.add(current)
            ordered.add(current)
            current = sortedAdj[current].orEmpty().firstOrNull { it in memberSet && it !in visited }
        }
        component.sorted().forEach { node ->
            if (node !in visited) {
                visited.add(node)
                ordered.add(node)
            }
        }
        return ordered
    }

    /**
     * Robert C. Martin Coupling Metrics:
     * - Ca (Afferent Coupling): distinct internal modules depending on this module
     * - Ce (Efferent Coupling): distinct internal modules this module depends on
     * - I (Instability): Ce / (Ca + Ce), or 0.0 when Ca + Ce == 0
     */
    fun computeCouplingMetrics(graph: DependencyGraph): List<CouplingMetrics> {
        val modules = graph.modules.sorted()
        val fanOut = modules.associateWith { sortedSetOf<String>() }
        val fanIn = modules.associateWith { sortedSetOf<String>() }

        for (entry in graph.moduleEdges) {
            if (entry.fromModule != entry.toModule) {
                fanOut[entry.fromModule]?.add(entry.toModule)
                fanIn[entry.toModule]?.add(entry.fromModule)
            }
        }

        return modules.map { module ->
            val inMods = fanIn[module].orEmpty().toList()
            val outMods = fanOut[module].orEmpty().toList()
            val ca = inMods.size
            val ce = outMods.size
            val instability = if (ca + ce == 0) {
                0.0
            } else {
                round((ce.toDouble() / (ca + ce).toDouble()) * 10000.0) / 10000.0
            }
            CouplingMetrics(
                module = module,
                afferentCoupling = ca,
                efferentCoupling = ce,
                instability = instability,
                fanInModules = inMods,
                fanOutModules = outMods,
            )
        }
    }

    fun analyze(graph: DependencyGraph): GraphAnalysisSummary {
        val moduleCycles = findCycles(graph.moduleAdjacency(), CycleGranularity.MODULE)
        val packageCycles = findCycles(graph.packageAdjacency(), CycleGranularity.PACKAGE)
        val coupling = computeCouplingMetrics(graph)

        val fanInHotspots = coupling
            .filter { it.afferentCoupling > 0 }
            .sortedWith(compareByDescending<CouplingMetrics> { it.afferentCoupling }.thenBy { it.module })
            .take(5)
            .map { it.module }

        val fanOutHotspots = coupling
            .filter { it.efferentCoupling > 0 }
            .sortedWith(compareByDescending<CouplingMetrics> { it.efferentCoupling }.thenBy { it.module })
            .take(5)
            .map { it.module }

        return GraphAnalysisSummary(
            cycles = moduleCycles,
            packageCycles = packageCycles,
            couplingMetrics = coupling,
            fanInHotspots = fanInHotspots,
            fanOutHotspots = fanOutHotspots,
        )
    }
}
