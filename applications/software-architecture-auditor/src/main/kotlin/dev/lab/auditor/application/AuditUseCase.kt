package dev.lab.auditor.application

import dev.lab.auditor.domain.AiAdvisoryStatus
import dev.lab.auditor.domain.AiSuggestionsEnvelope
import dev.lab.auditor.domain.AuditReport
import dev.lab.auditor.domain.GraphAlgorithms
import dev.lab.auditor.domain.RuleEngine
import dev.lab.auditor.domain.RuleSet
import dev.lab.auditor.domain.ScanDiff
import dev.lab.auditor.domain.ScanDiffer
import dev.lab.auditor.domain.VerifiedFindingsEnvelope
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class ScanNotFoundException(val scanId: String) : NoSuchElementException("Scan not found: $scanId")

class AuditUseCase(
    private val scanner: RepositoryScanner,
    private val repository: ScanRepositoryPort,
    private val aiAdvisor: AiAdvisorPort,
    private val auditEvents: AuditEventPort = NoOpAuditEventPort,
) {

    suspend fun runAudit(
        repositoryPath: Path,
        ruleSet: RuleSet,
        includeAiAdvice: Boolean = true,
        correlationId: String? = null,
        actor: String = "cli",
        strictSymlinkRejection: Boolean = false,
    ): AuditReport {
        val canonicalPath = repositoryPath.toAbsolutePath().normalize().toRealPath()
        val scanOutput = scanner.scan(canonicalPath, strictSymlinkRejection = strictSymlinkRejection)
        val analysis = GraphAlgorithms.analyze(scanOutput.graph)
        val violations = RuleEngine.evaluate(scanOutput.graph, analysis, ruleSet)
        val verifiedFindings = VerifiedFindingsEnvelope.from(scanOutput.graph, analysis, violations)

        val scanId = "scan-${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val now = Instant.now().toString()

        val initialReport = AuditReport(
            scanId = scanId,
            repositoryPath = canonicalPath.toString(),
            rulesetName = ruleSet.name,
            createdAt = now,
            graph = scanOutput.graph,
            analysis = analysis,
            telemetry = scanOutput.telemetry,
            verifiedFindings = verifiedFindings,
            aiSuggestions = AiSuggestionsEnvelope.disabled(aiAdvisor.providerName),
        )

        val finalReport = if (includeAiAdvice) {
            val aiEnvelope = generateAdvisoryEnvelope(
                report = initialReport,
                repoRoot = canonicalPath,
                correlationId = correlationId,
            )
            // Type-enforced immutability: verifiedFindings cannot be modified by AI advice
            initialReport.attachAdvisorySuggestions(aiEnvelope)
        } else {
            initialReport
        }

        repository.save(finalReport)
        auditEvents.record(
            eventType = "SCAN_COMPLETED",
            correlationId = correlationId,
            actor = actor,
            detail = mapOf(
                "scanId" to finalReport.scanId,
                "ruleset" to ruleSet.name,
                "filesScanned" to finalReport.telemetry.filesScanned.toString(),
                "violations" to finalReport.verifiedFindings.violationCount.toString(),
                "cycles" to finalReport.verifiedFindings.cycleCount.toString(),
                "aiStatus" to finalReport.aiSuggestions.status.name,
                "digest" to finalReport.verifiedFindings.deterministicDigest,
            ),
        )
        return finalReport
    }

    suspend fun explainExistingScan(
        scanId: String,
        correlationId: String? = null,
        actor: String = "api",
    ): AuditReport {
        val existing = repository.findById(scanId) ?: throw ScanNotFoundException(scanId)
        val repoRoot = runCatching { Path.of(existing.repositoryPath) }.getOrNull()
        val aiEnvelope = generateAdvisoryEnvelope(existing, repoRoot, correlationId)
        val updated = existing.attachAdvisorySuggestions(aiEnvelope)
        repository.updateAiSuggestions(scanId, updated.aiSuggestions)

        auditEvents.record(
            eventType = "AI_EXPLANATION_GENERATED",
            correlationId = correlationId,
            actor = actor,
            detail = mapOf(
                "scanId" to scanId,
                "provider" to updated.aiSuggestions.provider,
                "status" to updated.aiSuggestions.status.name,
                "suggestionCount" to updated.aiSuggestions.suggestions.size.toString(),
                "injectionNeutralized" to updated.aiSuggestions.injectionAttemptsNeutralized.toString(),
            ),
        )
        return updated
    }

    fun getScan(scanId: String): AuditReport =
        repository.findById(scanId) ?: throw ScanNotFoundException(scanId)

    fun listScans(limit: Int = 50): List<ScanSummary> =
        repository.listRecent(limit.coerceIn(1, 200))

    fun diffScans(baseScanId: String, headScanId: String): ScanDiff {
        val base = getScan(baseScanId)
        val head = getScan(headScanId)
        return ScanDiffer.diff(base.toSnapshot(), head.toSnapshot())
    }

    private suspend fun generateAdvisoryEnvelope(
        report: AuditReport,
        repoRoot: Path?,
        correlationId: String?,
    ): AiSuggestionsEnvelope {
        if (aiAdvisor.providerName.equals("none", ignoreCase = true)) {
            return AiSuggestionsEnvelope.disabled("none")
        }
        if (report.verifiedFindings.violations.isEmpty()) {
            return AiSuggestionsEnvelope.skippedNoViolations(aiAdvisor.providerName, aiAdvisor.modelName)
        }

        val snippets = report.verifiedFindings.violations
            .take(15)
            .map { v ->
                val text = extractSourceSnippet(repoRoot, v.evidenceFile, v.evidenceLine)
                ViolationSourceSnippet(
                    violationId = v.id,
                    filePath = v.evidenceFile,
                    lineNumber = v.evidenceLine,
                    snippet = text,
                )
            }

        return try {
            aiAdvisor.advise(
                AiRemediationRequest(
                    scanId = report.scanId,
                    repositoryName = Path.of(report.repositoryPath).fileName?.toString() ?: "repository",
                    verifiedFindings = report.verifiedFindings,
                    analysis = report.analysis,
                    codeSnippets = snippets,
                    correlationId = correlationId,
                ),
            )
        } catch (ex: Exception) {
            AiSuggestionsEnvelope(
                status = AiAdvisoryStatus.DEGRADED_UNAVAILABLE,
                provider = aiAdvisor.providerName,
                model = aiAdvisor.modelName,
                notice = "AI remediation advisor degraded gracefully (${ex.javaClass.simpleName}). Deterministic verified findings remain authoritative.",
                suggestions = emptyList(),
            )
        }
    }

    private fun extractSourceSnippet(repoRoot: Path?, relativeFile: String, line: Int): String {
        if (repoRoot == null || relativeFile.startsWith("<")) return ""
        val filePath = repoRoot.resolve(relativeFile).normalize()
        if (!filePath.startsWith(repoRoot) || !Files.isRegularFile(filePath)) return ""
        return runCatching {
            val lines = Files.readAllLines(filePath)
            val from = (line - 3).coerceAtLeast(1)
            val to = (line + 2).coerceAtMost(lines.size)
            if (from > to) "" else (from..to).joinToString("\n") { ln -> "$ln: ${lines[ln - 1]}" }
        }.getOrDefault("")
    }
}
