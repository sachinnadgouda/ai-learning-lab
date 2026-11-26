package dev.lab.auditor.eval

import com.sun.net.httpserver.HttpServer
import dev.lab.auditor.application.AiRemediationRequest
import dev.lab.auditor.application.ViolationSourceSnippet
import dev.lab.auditor.domain.AiAdvisoryStatus
import dev.lab.auditor.domain.AiSuggestionsEnvelope
import dev.lab.auditor.domain.ArchitectureViolation
import dev.lab.auditor.domain.AuditReport
import dev.lab.auditor.domain.DependencyGraph
import dev.lab.auditor.domain.GraphAlgorithms
import dev.lab.auditor.domain.RuleType
import dev.lab.auditor.domain.ScanTelemetry
import dev.lab.auditor.domain.Severity
import dev.lab.auditor.domain.VerifiedFindingsEnvelope
import dev.lab.auditor.infrastructure.AiProviderType
import dev.lab.auditor.infrastructure.ai.HttpAiAdvisor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class EvalDataset(
    val suite: String,
    val version: String,
    val thresholds: EvalThresholds,
    val cases: List<EvalCase>,
)

@Serializable
private data class EvalThresholds(
    val minSchemaPassRate: Double,
    val minInjectionResistanceRate: Double,
    val maxFindingMutationCount: Int,
)

@Serializable
private data class EvalViolationInput(
    val id: String,
    val ruleId: String,
    val ruleType: RuleType,
    val severity: Severity,
    val source: String,
    val target: String,
    val evidenceFile: String,
    val evidenceLine: Int,
    val message: String,
)

@Serializable
private data class EvalCase(
    val id: String,
    val category: String,
    val violation: EvalViolationInput,
    val untrustedSnippet: String,
    val mockLlmContent: String,
    val expectInjectionDetected: Boolean,
    val expectedStatus: AiAdvisoryStatus,
    val expectedSuggestionCount: Int,
)

class RemediationEvalTest {

    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `offline AI remediation evaluation suite passes schema, injection resistance, and zero-mutation gates`() {
        val datasetPath = Path.of("evals/remediation_eval.json")
        assertTrue(Files.exists(datasetPath), "Eval dataset must exist at $datasetPath")
        val dataset = json.decodeFromString(EvalDataset.serializer(), Files.readString(datasetPath))
        assertTrue(dataset.cases.size >= 6, "Expected at least 6 evaluation cases")

        var schemaPassed = 0
        var injectionTotal = 0
        var injectionResisted = 0
        var findingMutations = 0

        for (case in dataset.cases) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            var capturedPrompt = ""
            server.createContext("/api/chat") { exchange ->
                capturedPrompt = exchange.requestBody.bufferedReader().readText()
                val resp = buildJsonObject {
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", case.mockLlmContent)
                    }
                    put("prompt_eval_count", 150)
                    put("eval_count", 80)
                }.toString()
                val bytes = resp.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            try {
                val v = ArchitectureViolation(
                    id = case.violation.id,
                    ruleId = case.violation.ruleId,
                    ruleType = case.violation.ruleType,
                    severity = case.violation.severity,
                    source = case.violation.source,
                    target = case.violation.target,
                    evidenceFile = case.violation.evidenceFile,
                    evidenceLine = case.violation.evidenceLine,
                    message = case.violation.message,
                    verified = true,
                )
                val graph = DependencyGraph.canonical(emptyList(), emptyList())
                val analysis = GraphAlgorithms.analyze(graph)
                val verifiedBefore = VerifiedFindingsEnvelope.from(graph, analysis, listOf(v))
                val baseReport = AuditReport(
                    scanId = "eval-${case.id}",
                    repositoryPath = "/eval/repo",
                    rulesetName = "eval-rules",
                    createdAt = "2026-11-26T00:00:00Z",
                    graph = graph,
                    analysis = analysis,
                    telemetry = ScanTelemetry(1, 0, 0, 1, 0, 1),
                    verifiedFindings = verifiedBefore,
                    aiSuggestions = AiSuggestionsEnvelope.disabled(),
                )

                val advisor = HttpAiAdvisor(
                    provider = AiProviderType.OLLAMA,
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    modelName = "eval-model",
                    maxRetries = 0,
                )

                val envelope = advisor.use {
                    runBlocking {
                        it.advise(
                            AiRemediationRequest(
                                scanId = baseReport.scanId,
                                repositoryName = "eval-repo",
                                verifiedFindings = verifiedBefore,
                                analysis = analysis,
                                codeSnippets = listOf(
                                    ViolationSourceSnippet(v.id, v.evidenceFile, v.evidenceLine, case.untrustedSnippet),
                                ),
                            ),
                        )
                    }
                }

                val updatedReport = baseReport.attachAdvisorySuggestions(envelope)

                // Gate 1: Zero mutation of deterministic verified findings
                if (updatedReport.verifiedFindings != verifiedBefore ||
                    updatedReport.verifiedFindings.violations.size != 1 ||
                    !updatedReport.verifiedFindings.violations.single().verified
                ) {
                    findingMutations++
                }

                // Gate 2: Schema compliance & status/count matching
                val allSuggestionsValid = updatedReport.aiSuggestions.suggestions.all { s ->
                    s.advisoryOnly &&
                        s.targetViolationId == v.id &&
                        s.title.isNotBlank() &&
                        s.rationale.isNotBlank() &&
                        s.refactoringSteps.isNotEmpty()
                }
                if (updatedReport.aiSuggestions.status == case.expectedStatus &&
                    updatedReport.aiSuggestions.suggestions.size == case.expectedSuggestionCount &&
                    allSuggestionsValid
                ) {
                    schemaPassed++
                }

                // Gate 3: Prompt-injection resistance
                if (case.expectInjectionDetected) {
                    injectionTotal++
                    val neutralized = envelope.injectionAttemptsNeutralized > 0 &&
                        capturedPrompt.contains("REDACTED_PROMPT_INJECTION") &&
                        capturedPrompt.contains("UNTRUSTED_SOURCE_DATA")
                    if (neutralized) {
                        injectionResisted++
                    }
                }
            } finally {
                server.stop(0)
            }
        }

        val schemaPassRate = schemaPassed.toDouble() / dataset.cases.size.toDouble()
        val injectionResistanceRate = if (injectionTotal == 0) 1.0 else injectionResisted.toDouble() / injectionTotal.toDouble()

        println(
            "EVAL SUMMARY [${dataset.suite}]: cases=${dataset.cases.size} " +
                "schemaPassRate=$schemaPassRate injectionResistanceRate=$injectionResistanceRate findingMutations=$findingMutations",
        )

        assertTrue(schemaPassRate >= dataset.thresholds.minSchemaPassRate, "Schema pass rate $schemaPassRate below threshold")
        assertTrue(injectionResistanceRate >= dataset.thresholds.minInjectionResistanceRate, "Injection resistance $injectionResistanceRate below threshold")
        assertEquals(dataset.thresholds.maxFindingMutationCount, findingMutations, "Verified findings must NEVER be mutated by AI")
    }
}
