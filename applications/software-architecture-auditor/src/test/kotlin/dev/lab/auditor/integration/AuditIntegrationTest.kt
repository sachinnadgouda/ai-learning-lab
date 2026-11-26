package dev.lab.auditor.integration

import dev.lab.auditor.application.AuditUseCase
import dev.lab.auditor.application.RepositoryScanner
import dev.lab.auditor.domain.RuleLoader
import dev.lab.auditor.infrastructure.ai.DisabledAiAdvisor
import dev.lab.auditor.infrastructure.persistence.SqliteScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditIntegrationTest {

    private val cleanupPaths = mutableListOf<Path>()

    private fun testWorkDir(name: String): Path {
        val base = Path.of("build/test-tmp").toAbsolutePath().normalize()
        Files.createDirectories(base)
        val dir = base.resolve("$name-${UUID.randomUUID().toString().take(8)}")
        Files.createDirectories(dir)
        cleanupPaths.add(dir)
        return dir
    }

    @AfterEach
    fun cleanup() {
        cleanupPaths.forEach { p ->
            if (Files.exists(p)) {
                Files.walk(p).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        cleanupPaths.clear()
    }

    @Test
    fun `clean-hexagonal fixture passes with zero violations and zero cycles`() {
        val dir = testWorkDir("clean")
        val repo = SqliteScanRepository(dir.resolve("auditor.db").toString())
        val useCase = AuditUseCase(RepositoryScanner(repo), repo, DisabledAiAdvisor(), repo)
        val rules = RuleLoader.loadFromFile(Path.of("examples/rules.yaml"))

        val report = runBlocking {
            useCase.runAudit(Path.of("examples/clean-hexagonal"), rules)
        }

        assertEquals(0, report.verifiedFindings.violationCount, "Expected zero violations, got: ${report.verifiedFindings.violations}")
        assertEquals(0, report.analysis.cycles.size)
        assertEquals(5, report.telemetry.filesScanned)
        assertEquals(0, report.telemetry.parseWarningCount)
        assertTrue(repo.countAuditEvents() >= 1)
    }

    @Test
    fun `violating-monolith fixture detects layer violation, 3-node cycle, high Ce, and partial syntax warning with zero false positives`() {
        val dir = testWorkDir("monolith")
        val repo = SqliteScanRepository(dir.resolve("auditor.db").toString())
        val useCase = AuditUseCase(RepositoryScanner(repo), repo, DisabledAiAdvisor(), repo)
        val rules = RuleLoader.loadFromFile(Path.of("examples/rules.yaml"))

        val report = runBlocking {
            useCase.runAudit(Path.of("examples/violating-monolith"), rules)
        }

        val violations = report.verifiedFindings.violations
        assertTrue(violations.any { it.ruleId == "domain-no-infrastructure" && it.source == "domain" && it.target == "infrastructure" })
        assertTrue(violations.any { it.ruleId == "no-module-cycles" })
        assertTrue(violations.any { it.ruleId == "max-module-efferent-coupling" && it.source == "orchestrator" })
        assertTrue(violations.any { it.ruleId == "domain-max-instability" && it.source == "domain" })

        // Verify 3-node cycle billing -> orders -> inventory
        assertEquals(1, report.analysis.cycles.size)
        assertEquals(listOf("billing", "orders", "inventory"), report.analysis.cycles.single().nodes)

        // Verify zero false positives for domain -> api (only present inside comments and string literals)
        assertFalse(violations.any { it.ruleId == "domain-no-api" }, "Comment/string import trap must not trigger domain-no-api")

        // Verify partially invalid file produced non-fatal parse warning without aborting scan
        assertTrue(report.telemetry.parseWarningCount >= 1)
        assertEquals(10, report.telemetry.filesScanned)
    }

    @Test
    fun `incremental scanning hits SQLite cache on unchanged files and re-parses only modified files`() {
        val dir = testWorkDir("incremental")
        val repoDir = dir.resolve("sample-repo")
        Files.createDirectories(repoDir.resolve("domain"))
        Files.createDirectories(repoDir.resolve("application"))
        Files.writeString(repoDir.resolve("domain/Item.kt"), "package com.demo.domain\ndata class Item(val id: String)\n")
        Files.writeString(repoDir.resolve("application/ItemSvc.kt"), "package com.demo.application\nimport com.demo.domain.Item\nclass ItemSvc\n")

        val dbRepo = SqliteScanRepository(dir.resolve("cache.db").toString())
        val useCase = AuditUseCase(RepositoryScanner(dbRepo), dbRepo, DisabledAiAdvisor(), dbRepo)
        val rules = RuleLoader.defaultHexagonalRuleSet()

        val scan1 = runBlocking { useCase.runAudit(repoDir, rules) }
        assertEquals(0, scan1.telemetry.cacheHits)
        assertEquals(2, scan1.telemetry.cacheMisses)

        // Second scan on identical files: 100% cache hits and identical deterministic digest
        val scan2 = runBlocking { useCase.runAudit(repoDir, rules) }
        assertEquals(2, scan2.telemetry.cacheHits)
        assertEquals(0, scan2.telemetry.cacheMisses)
        assertEquals(scan1.graph.deterministicDigest, scan2.graph.deterministicDigest)
        assertEquals(scan1.verifiedFindings.deterministicDigest, scan2.verifiedFindings.deterministicDigest)

        // Modify one file: 1 cache hit, 1 cache miss
        Files.writeString(repoDir.resolve("domain/Item.kt"), "package com.demo.domain\ndata class Item(val id: String, val name: String)\n")
        val scan3 = runBlocking { useCase.runAudit(repoDir, rules) }
        assertEquals(1, scan3.telemetry.cacheHits)
        assertEquals(1, scan3.telemetry.cacheMisses)
    }

    @Test
    fun `SQLite scan history and ScanDiff detect architectural regressions and resolutions`() {
        val dir = testWorkDir("diff")
        val repoDir = dir.resolve("evolving-repo")
        Files.createDirectories(repoDir.resolve("domain"))
        Files.createDirectories(repoDir.resolve("infrastructure"))
        Files.writeString(repoDir.resolve("infrastructure/Db.kt"), "package com.app.infrastructure\nclass Db\n")
        Files.writeString(repoDir.resolve("domain/User.kt"), "package com.app.domain\ndata class User(val id: String)\n")

        val dbRepo = SqliteScanRepository(dir.resolve("history.db").toString())
        val useCase = AuditUseCase(RepositoryScanner(dbRepo), dbRepo, DisabledAiAdvisor(), dbRepo)
        val rules = RuleLoader.defaultHexagonalRuleSet()

        val baseScan = runBlocking { useCase.runAudit(repoDir, rules) }
        assertEquals(0, baseScan.verifiedFindings.violationCount)

        // Introduce forbidden domain -> infrastructure dependency
        Files.writeString(
            repoDir.resolve("domain/User.kt"),
            "package com.app.domain\nimport com.app.infrastructure.Db\nclass User(val db: Db)\n",
        )
        val regressedScan = runBlocking { useCase.runAudit(repoDir, rules) }
        val regressionDiff = useCase.diffScans(baseScan.scanId, regressedScan.scanId)
        assertTrue(regressionDiff.regressionDetected)
        assertTrue(regressionDiff.newViolations.any { it.ruleId == "domain-no-infra" })
        assertTrue(regressionDiff.couplingDeltas.any { it.module == "domain" && it.currentCe == 1 })

        // Resolve the violation
        Files.writeString(repoDir.resolve("domain/User.kt"), "package com.app.domain\ndata class User(val id: String)\n")
        val fixedScan = runBlocking { useCase.runAudit(repoDir, rules) }
        val resolutionDiff = useCase.diffScans(regressedScan.scanId, fixedScan.scanId)
        assertFalse(resolutionDiff.regressionDetected)
        assertTrue(resolutionDiff.newViolations.isEmpty())
        assertTrue(resolutionDiff.resolvedViolations.any { it.ruleId == "domain-no-infra" })
    }

    @Test
    fun `scans large 500-file synthetic polyglot repository quickly within bounded limits`() {
        val dir = testWorkDir("large-500")
        val repoDir = dir.resolve("synthetic-500")
        val modules = (0 until 10).map { "mod$it" }
        modules.forEach { Files.createDirectories(repoDir.resolve(it)) }

        for (i in 0 until 500) {
            val modIdx = i % 10
            val mod = modules[modIdx]
            val targetImport = if (modIdx > 0) "import com.synth.mod${modIdx - 1}.Comp${i - 1}\n" else ""
            Files.writeString(
                repoDir.resolve("$mod/Comp$i.kt"),
                "package com.synth.$mod\n$targetImport\nclass Comp$i\n",
            )
        }

        val dbRepo = SqliteScanRepository(dir.resolve("large.db").toString())
        val useCase = AuditUseCase(RepositoryScanner(dbRepo), dbRepo, DisabledAiAdvisor(), dbRepo)
        val rules = RuleLoader.defaultHexagonalRuleSet()

        val startMs = System.currentTimeMillis()
        val report = runBlocking { useCase.runAudit(repoDir, rules) }
        val elapsedMs = System.currentTimeMillis() - startMs

        assertEquals(500, report.telemetry.filesScanned)
        assertEquals(10, report.graph.modules.size)
        assertEquals(0, report.analysis.cycles.size)
        assertTrue(elapsedMs < 15_000L, "Expected 500-file scan to complete rapidly, took ${elapsedMs}ms")
    }
}
