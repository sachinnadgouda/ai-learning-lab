package dev.lab.auditor.application

import dev.lab.auditor.domain.RuleLoader
import dev.lab.auditor.infrastructure.ai.DisabledAiAdvisor
import dev.lab.auditor.infrastructure.persistence.SqliteScanRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportExporterTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        tempFiles.forEach { Files.deleteIfExists(it) }
        tempFiles.clear()
    }

    @Test
    fun `exports valid JSON, SARIF 2_1_0, Markdown, and Mermaid with cycle and violation highlights`() {
        val db = Path.of("build/test-tmp/exporter-${UUID.randomUUID().toString().take(8)}.db")
        tempFiles.add(db)
        val repo = SqliteScanRepository(db.toString())
        val scanner = RepositoryScanner(repo)
        val useCase = AuditUseCase(scanner, repo, DisabledAiAdvisor(), repo)
        val rules = RuleLoader.loadFromFile(Path.of("examples/rules.yaml"))

        val report = runBlocking {
            useCase.runAudit(Path.of("examples/violating-monolith"), rules)
        }

        // JSON
        val jsonOut = ReportExporter.export(report, ExportFormat.JSON)
        val parsedJson = Json.parseToJsonElement(jsonOut).jsonObject
        assertTrue(parsedJson.containsKey("verifiedFindings"))
        assertTrue(parsedJson.containsKey("aiSuggestions"))

        // SARIF 2.1.0
        val sarifOut = ReportExporter.export(report, ExportFormat.SARIF)
        val sarif = Json.parseToJsonElement(sarifOut).jsonObject
        assertEquals("2.1.0", sarif["version"]?.jsonPrimitive?.content)
        val runs = sarif["runs"]?.jsonArray
        assertEquals(1, runs?.size)
        val results = runs?.first()?.jsonObject?.get("results")?.jsonArray
        assertEquals(report.verifiedFindings.violationCount, results?.size)

        // Mermaid
        val mermaidOut = ReportExporter.export(report, ExportFormat.MERMAID)
        assertTrue(mermaidOut.startsWith("graph LR"))
        assertTrue(mermaidOut.contains("(CYCLE)"))
        assertTrue(mermaidOut.contains("(VIOLATION)"))
        assertTrue(mermaidOut.contains("linkStyle"))

        // Markdown
        val mdOut = ReportExporter.export(report, ExportFormat.MARKDOWN)
        assertTrue(mdOut.contains("# Software Architecture Audit Report"))
        assertTrue(mdOut.contains("## 1. Verified Architectural Findings (Deterministic)"))
        assertTrue(mdOut.contains("## 4. AI Advisory Remediation (Non-Authoritative)"))
        assertTrue(mdOut.contains("```mermaid"))
    }
}
