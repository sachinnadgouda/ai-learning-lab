package dev.lab.auditor.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphAlgorithmsTest {

    @Test
    fun `detects 2-node cycle canonically`() {
        val adj = mapOf(
            "orders" to setOf("billing"),
            "billing" to setOf("orders"),
            "domain" to emptySet(),
        )
        val cycles = GraphAlgorithms.findCycles(adj, CycleGranularity.MODULE)
        assertEquals(1, cycles.size)
        assertEquals(listOf("billing", "orders"), cycles.single().nodes)
    }

    @Test
    fun `detects 3-node cycle and self-loop`() {
        val adj = mapOf(
            "billing" to setOf("orders"),
            "orders" to setOf("inventory"),
            "inventory" to setOf("billing"),
            "legacy" to setOf("legacy"),
            "core" to emptySet(),
        )
        val cycles = GraphAlgorithms.findCycles(adj, CycleGranularity.MODULE)
        assertEquals(2, cycles.size)
        assertEquals(listOf("billing", "orders", "inventory"), cycles[0].nodes)
        assertEquals(listOf("legacy"), cycles[1].nodes)
    }

    @Test
    fun `detects nested overlapping cycles as single strongly connected component`() {
        val adj = mapOf(
            "a" to setOf("b"),
            "b" to setOf("c", "a"),
            "c" to setOf("a"),
            "d" to setOf("e"),
            "e" to setOf("d"),
        )
        val cycles = GraphAlgorithms.findCycles(adj, CycleGranularity.MODULE)
        assertEquals(2, cycles.size)
        assertEquals(setOf("a", "b", "c"), cycles[0].nodes.toSet())
        assertEquals(listOf("d", "e"), cycles[1].nodes)
    }

    @Test
    fun `returns zero cycles on acyclic DAG`() {
        val adj = mapOf(
            "api" to setOf("application"),
            "infrastructure" to setOf("application", "domain"),
            "application" to setOf("domain"),
            "domain" to emptySet(),
        )
        val cycles = GraphAlgorithms.findCycles(adj, CycleGranularity.MODULE)
        assertTrue(cycles.isEmpty())
    }

    @Test
    fun `computes Robert C Martin coupling and instability metrics accurately`() {
        val nodes = listOf(
            SourceFileNode("domain/A.kt", Language.KOTLIN, "domain", "p.domain", "h1", 10),
            SourceFileNode("application/B.kt", Language.KOTLIN, "application", "p.application", "h2", 10),
            SourceFileNode("infrastructure/C.kt", Language.KOTLIN, "infrastructure", "p.infrastructure", "h3", 10),
            SourceFileNode("api/D.kt", Language.KOTLIN, "api", "p.api", "h4", 10),
            SourceFileNode("isolated/E.kt", Language.KOTLIN, "isolated", "p.isolated", "h5", 10),
        )
        val edges = listOf(
            DependencyEdge("application/B.kt", "application", "p.domain", "domain", "p.domain.A", 2),
            DependencyEdge("infrastructure/C.kt", "infrastructure", "p.domain", "domain", "p.domain.A", 2),
            DependencyEdge("infrastructure/C.kt", "infrastructure", "p.application", "application", "p.application.B", 3),
            DependencyEdge("api/D.kt", "api", "p.application", "application", "p.application.B", 2),
        )
        val graph = DependencyGraph.canonical(nodes, edges)
        val metrics = GraphAlgorithms.computeCouplingMetrics(graph).associateBy { it.module }

        // domain: Ca=2 (application, infrastructure), Ce=0 => I=0.0
        assertEquals(2, metrics.getValue("domain").afferentCoupling)
        assertEquals(0, metrics.getValue("domain").efferentCoupling)
        assertEquals(0.0, metrics.getValue("domain").instability)

        // application: Ca=2 (api, infrastructure), Ce=1 (domain) => I=1/3=0.3333
        assertEquals(2, metrics.getValue("application").afferentCoupling)
        assertEquals(1, metrics.getValue("application").efferentCoupling)
        assertEquals(0.3333, metrics.getValue("application").instability)

        // infrastructure: Ca=0, Ce=2 (application, domain) => I=1.0
        assertEquals(0, metrics.getValue("infrastructure").afferentCoupling)
        assertEquals(2, metrics.getValue("infrastructure").efferentCoupling)
        assertEquals(1.0, metrics.getValue("infrastructure").instability)

        // isolated: Ca=0, Ce=0 => I=0.0
        assertEquals(0.0, metrics.getValue("isolated").instability)
    }
}
