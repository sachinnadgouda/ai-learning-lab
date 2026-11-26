package dev.lab.auditor.infrastructure

import dev.lab.auditor.application.AuditUseCase
import dev.lab.auditor.application.RepositoryScanner
import dev.lab.auditor.domain.AuditReport
import dev.lab.auditor.domain.ScanDiff
import dev.lab.auditor.infrastructure.ai.DisabledAiAdvisor
import dev.lab.auditor.infrastructure.api.CreateScanRequest
import dev.lab.auditor.infrastructure.api.ProblemDetails
import dev.lab.auditor.infrastructure.api.configureAuditorServer
import dev.lab.auditor.infrastructure.persistence.SqliteScanRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpServerApiTest {

    private val testApiKey = "test-super-secret-api-key-123456"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val tempDirs = mutableListOf<Path>()

    private fun newDbPath(): String {
        val base = Path.of("build/test-tmp/api-${UUID.randomUUID().toString().take(8)}").toAbsolutePath().normalize()
        Files.createDirectories(base)
        tempDirs.add(base)
        return base.resolve("api.db").toString()
    }

    @AfterEach
    fun cleanup() {
        tempDirs.forEach { d ->
            if (Files.exists(d)) {
                Files.walk(d).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tempDirs.clear()
    }

    @Test
    fun `health, ready, and metrics are public while api v1 requires valid X-API-Key`() = testApplication {
        val repo = SqliteScanRepository(newDbPath())
        val metrics = AuditorMetricsRegistry()
        val config = AuditorConfig(
            apiKey = testApiKey,
            allowedScanRoots = listOf(Path.of("examples").toAbsolutePath().normalize()),
        )
        val scanner = RepositoryScanner(repo, config.toScannerLimits())
        val useCase = AuditUseCase(scanner, repo, DisabledAiAdvisor(), repo)

        application {
            configureAuditorServer(config, useCase, scanner, repo, metrics)
        }

        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertTrue(health.headers["X-Correlation-ID"]?.isNotBlank() == true)

        val ready = client.get("/ready")
        assertEquals(HttpStatusCode.OK, ready.status)

        val unauth = client.get("/api/v1/scans")
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)
        assertEquals("application/problem+json", unauth.contentType()?.withoutParameters().toString())
        val problem = json.decodeFromString(ProblemDetails.serializer(), unauth.bodyAsText())
        assertEquals(401, problem.status)

        val wrongKey = client.get("/api/v1/scans") {
            header("X-API-Key", "wrong-key")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongKey.status)
    }

    @Test
    fun `enforces AUDITOR_ALLOWED_ROOTS allowlist and rejects invalid rules with RFC 9457 Problem Details`() = testApplication {
        val repo = SqliteScanRepository(newDbPath())
        val metrics = AuditorMetricsRegistry()
        val config = AuditorConfig(
            apiKey = testApiKey,
            allowedScanRoots = listOf(Path.of("examples").toAbsolutePath().normalize()),
        )
        val scanner = RepositoryScanner(repo, config.toScannerLimits())
        val useCase = AuditUseCase(scanner, repo, DisabledAiAdvisor(), repo)

        application {
            configureAuditorServer(config, useCase, scanner, repo, metrics)
        }

        // Attempt to scan outside allowed roots (project src directory)
        val forbiddenResp = client.post("/api/v1/scans") {
            header("X-API-Key", testApiKey)
            header("X-Correlation-ID", "corr-test-403")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateScanRequest.serializer(), CreateScanRequest(repositoryPath = "src")))
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenResp.status)
        assertEquals("corr-test-403", forbiddenResp.headers["X-Correlation-ID"])
        val problem403 = json.decodeFromString(ProblemDetails.serializer(), forbiddenResp.bodyAsText())
        assertEquals(403, problem403.status)
        assertEquals("corr-test-403", problem403.correlationId)

        // Invalid YAML rules returns 422
        val invalidRulesResp = client.post("/api/v1/scans") {
            header("X-API-Key", testApiKey)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    CreateScanRequest.serializer(),
                    CreateScanRequest(
                        repositoryPath = "examples/clean-hexagonal",
                        rulesYaml = "version: '1.0'\nname: bad\nrules:\n  - id: r1\n    name: bad\n    type: MAX_INSTABILITY\n    maxThreshold: 9.9\n",
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, invalidRulesResp.status)
        val problem422 = json.decodeFromString(ProblemDetails.serializer(), invalidRulesResp.bodyAsText())
        assertEquals(422, problem422.status)
    }

    @Test
    fun `full API lifecycle scan, retrieve, export sarif and mermaid, diff, explain, and prometheus metrics`() = testApplication {
        val repo = SqliteScanRepository(newDbPath())
        val metrics = AuditorMetricsRegistry()
        val config = AuditorConfig(
            apiKey = testApiKey,
            allowedScanRoots = listOf(Path.of("examples").toAbsolutePath().normalize()),
        )
        val scanner = RepositoryScanner(repo, config.toScannerLimits())
        val useCase = AuditUseCase(scanner, repo, DisabledAiAdvisor(), repo)

        application {
            configureAuditorServer(config, useCase, scanner, repo, metrics)
        }

        val scanCleanResp = client.post("/api/v1/scans") {
            header("X-API-Key", testApiKey)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    CreateScanRequest.serializer(),
                    CreateScanRequest(
                        repositoryPath = "examples/clean-hexagonal",
                        rulesPath = "examples/rules.yaml",
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, scanCleanResp.status)
        val cleanReport = json.decodeFromString(AuditReport.serializer(), scanCleanResp.bodyAsText())
        assertEquals(0, cleanReport.verifiedFindings.violationCount)

        val scanBadResp = client.post("/api/v1/scans") {
            header("X-API-Key", testApiKey)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    CreateScanRequest.serializer(),
                    CreateScanRequest(
                        repositoryPath = "examples/violating-monolith",
                        rulesPath = "examples/rules.yaml",
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, scanBadResp.status)
        val badReport = json.decodeFromString(AuditReport.serializer(), scanBadResp.bodyAsText())
        assertTrue(badReport.verifiedFindings.violationCount >= 4)

        // Fetch by ID
        val getResp = client.get("/api/v1/scans/${badReport.scanId}") {
            header("X-API-Key", testApiKey)
        }
        assertEquals(HttpStatusCode.OK, getResp.status)

        // Export SARIF
        val sarifResp = client.get("/api/v1/scans/${badReport.scanId}/export?format=sarif") {
            header("X-API-Key", testApiKey)
        }
        assertEquals(HttpStatusCode.OK, sarifResp.status)
        assertTrue(sarifResp.bodyAsText().contains("\"version\": \"2.1.0\""))

        // Export Mermaid
        val mermaidResp = client.get("/api/v1/scans/${badReport.scanId}/export?format=mermaid") {
            header("X-API-Key", testApiKey)
        }
        assertEquals(HttpStatusCode.OK, mermaidResp.status)
        assertTrue(mermaidResp.bodyAsText().contains("graph LR"))

        // Diff
        val diffResp = client.get("/api/v1/scans/diff?base=${cleanReport.scanId}&head=${badReport.scanId}") {
            header("X-API-Key", testApiKey)
        }
        assertEquals(HttpStatusCode.OK, diffResp.status)
        val diff = json.decodeFromString(ScanDiff.serializer(), diffResp.bodyAsText())
        assertTrue(diff.regressionDetected)
        assertTrue(diff.newViolations.isNotEmpty())

        // Explain
        val explainResp = client.post("/api/v1/scans/${badReport.scanId}/explain") {
            header("X-API-Key", testApiKey)
        }
        assertEquals(HttpStatusCode.OK, explainResp.status)

        // Metrics
        val metricsResp = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, metricsResp.status)
        val prom = metricsResp.bodyAsText()
        assertTrue(prom.contains("auditor_scans_total 2"))
        assertTrue(prom.contains("auditor_http_requests_total"))
    }
}
