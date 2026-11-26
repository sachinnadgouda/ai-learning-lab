package dev.lab.auditor.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleEngineTest {

    private fun sampleGraph(): DependencyGraph {
        val nodes = listOf(
            SourceFileNode("domain/Order.kt", Language.KOTLIN, "domain", "com.app.domain", "h1", 20),
            SourceFileNode("application/UseCase.kt", Language.KOTLIN, "application", "com.app.application", "h2", 20),
            SourceFileNode("infrastructure/Repo.kt", Language.KOTLIN, "infrastructure", "com.app.infrastructure", "h3", 20),
            SourceFileNode("billing/Bill.kt", Language.KOTLIN, "billing", "com.app.billing", "h4", 20),
            SourceFileNode("orders/Ord.kt", Language.KOTLIN, "orders", "com.app.orders", "h5", 20),
            SourceFileNode("hub/Hub.kt", Language.KOTLIN, "hub", "com.app.hub", "h6", 20),
        )
        val edges = listOf(
            DependencyEdge("domain/Order.kt", "domain", "com.app.infrastructure", "infrastructure", "com.app.infrastructure.Repo", 4),
            DependencyEdge("application/UseCase.kt", "application", "com.app.domain", "domain", "com.app.domain.Order", 3),
            DependencyEdge("application/UseCase.kt", "application", "com.app.infrastructure", "infrastructure", "com.app.infrastructure.Repo", 5),
            DependencyEdge("billing/Bill.kt", "billing", "com.app.orders", "orders", "com.app.orders.Ord", 3),
            DependencyEdge("orders/Ord.kt", "orders", "com.app.billing", "billing", "com.app.billing.Bill", 3),
            DependencyEdge("hub/Hub.kt", "hub", "com.app.domain", "domain", "com.app.domain.Order", 3),
            DependencyEdge("hub/Hub.kt", "hub", "com.app.billing", "billing", "com.app.billing.Bill", 4),
            DependencyEdge("hub/Hub.kt", "hub", "com.app.orders", "orders", "com.app.orders.Ord", 5),
        )
        return DependencyGraph.canonical(nodes, edges)
    }

    @Test
    fun `evaluates all five rule types deterministically`() {
        val graph = sampleGraph()
        val analysis = GraphAlgorithms.analyze(graph)
        val ruleSet = RuleSet(
            name = "all-five-types",
            rules = listOf(
                ArchitectureRule(
                    id = "r1",
                    name = "Forbidden domain->infra",
                    type = RuleType.FORBIDDEN_DEPENDENCY,
                    severity = Severity.CRITICAL,
                    sourcePattern = "domain",
                    targetPattern = "infrastructure",
                ),
                ArchitectureRule(
                    id = "r2",
                    name = "Application allowed only domain",
                    type = RuleType.ALLOWED_DEPENDENCIES_ONLY,
                    severity = Severity.HIGH,
                    sourcePattern = "application",
                    allowedTargets = listOf("domain"),
                ),
                ArchitectureRule(
                    id = "r3",
                    name = "No module cycles",
                    type = RuleType.NO_CYCLES,
                    severity = Severity.HIGH,
                    granularity = CycleGranularity.MODULE,
                ),
                ArchitectureRule(
                    id = "r4",
                    name = "Max efferent coupling 2",
                    type = RuleType.MAX_EFFERENT_COUPLING,
                    severity = Severity.MEDIUM,
                    maxThreshold = 2.0,
                ),
                ArchitectureRule(
                    id = "r5",
                    name = "Domain max instability 0.2",
                    type = RuleType.MAX_INSTABILITY,
                    severity = Severity.HIGH,
                    sourcePattern = "domain",
                    maxThreshold = 0.2,
                ),
            ),
        )

        val violations = RuleEngine.evaluate(graph, analysis, ruleSet)
        val byRule = violations.groupBy { it.ruleId }

        assertEquals(1, byRule["r1"]?.size)
        assertEquals(1, byRule["r2"]?.size)
        assertEquals("infrastructure", byRule["r2"]?.single()?.target)
        assertEquals(1, byRule["r3"]?.size)
        assertTrue(byRule["r3"]?.single()?.message?.contains("billing -> orders -> billing") == true)
        assertEquals(1, byRule["r4"]?.size)
        assertEquals("hub", byRule["r4"]?.single()?.source)
        assertEquals(1, byRule["r5"]?.size)
        assertEquals("domain", byRule["r5"]?.single()?.source)
        assertTrue(violations.all { it.verified })
    }

    @Test
    fun `rejects invalid regex, empty patterns, duplicate IDs, and out-of-range thresholds`() {
        val badRuleSet = RuleSet(
            name = "broken",
            rules = listOf(
                ArchitectureRule(
                    id = "dup",
                    name = "Bad regex",
                    type = RuleType.FORBIDDEN_DEPENDENCY,
                    sourcePattern = "regex:[unclosed",
                    targetPattern = "",
                ),
                ArchitectureRule(
                    id = "dup",
                    name = "Bad instability",
                    type = RuleType.MAX_INSTABILITY,
                    maxThreshold = 1.5,
                ),
                ArchitectureRule(
                    id = "neg-ce",
                    name = "Negative Ce",
                    type = RuleType.MAX_EFFERENT_COUPLING,
                    maxThreshold = -1.0,
                ),
            ),
        )
        val ex = assertThrows<RuleValidationException> {
            RuleValidator.validate(badRuleSet)
        }
        assertTrue(ex.errors.any { it.contains("invalid pattern") })
        assertTrue(ex.errors.any { it.contains("duplicate rule id") })
        assertTrue(ex.errors.any { it.contains("[0.0, 1.0]") })
        assertTrue(ex.errors.any { it.contains("non-negative") })
    }

    @Test
    fun `loads valid YAML and rejects malformed YAML`() {
        val yaml = """
            version: "1.0"
            name: "test-yaml"
            rules:
              - id: "no-cycles"
                name: "Acyclic"
                type: NO_CYCLES
                severity: HIGH
                granularity: MODULE
        """.trimIndent()
        val parsed = RuleLoader.parseYaml(yaml)
        assertEquals("test-yaml", parsed.name)
        assertEquals(1, parsed.rules.size)

        assertThrows<RuleValidationException> {
            RuleLoader.parseYaml("rules: not-a-list")
        }
        assertThrows<RuleValidationException> {
            RuleLoader.parseYaml("")
        }
    }
}
