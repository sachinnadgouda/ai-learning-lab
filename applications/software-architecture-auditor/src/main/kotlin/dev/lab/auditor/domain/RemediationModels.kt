package dev.lab.auditor.domain

import kotlinx.serialization.Serializable

/**
 * Immutable authoritative envelope containing ONLY deterministic rule findings.
 * No AI adapter or remediation component receives mutable access to this structure.
 */
@Serializable
data class VerifiedFindingsEnvelope(
    val deterministicDigest: String,
    val violationCount: Int,
    val cycleCount: Int,
    val violations: List<ArchitectureViolation>,
) {
    init {
        require(violationCount == violations.size) {
            "violationCount ($violationCount) must equal violations.size (${violations.size})"
        }
        require(violations.all { it.verified }) {
            "All findings in VerifiedFindingsEnvelope must be deterministically verified"
        }
    }

    companion object {
        fun from(graph: DependencyGraph, analysis: GraphAnalysisSummary, violations: List<ArchitectureViolation>): VerifiedFindingsEnvelope {
            val digestInput = buildString {
                append(graph.deterministicDigest).append('\n')
                violations.forEach { v ->
                    append(v.id).append('|').append(v.ruleId).append('|').append(v.severity).append('\n')
                }
            }
            return VerifiedFindingsEnvelope(
                deterministicDigest = sha256Hex(digestInput),
                violationCount = violations.size,
                cycleCount = analysis.cycles.size,
                violations = violations.toList(),
            )
        }
    }
}

@Serializable
enum class AiAdvisoryStatus {
    COMPLETED,
    DISABLED,
    SKIPPED_NO_VIOLATIONS,
    DEGRADED_UNAVAILABLE,
    DEGRADED_INVALID_RESPONSE,
}

@Serializable
data class AiRemediationSuggestion(
    val targetViolationId: String,
    val title: String,
    val rationale: String,
    val refactoringSteps: List<String>,
    val estimatedEffort: String,
    val confidenceNote: String,
    val advisoryOnly: Boolean = true,
) {
    init {
        require(advisoryOnly) { "AI remediation suggestions are strictly advisoryOnly=true" }
        require(targetViolationId.isNotBlank()) { "targetViolationId must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(rationale.isNotBlank()) { "rationale must not be blank" }
        require(refactoringSteps.isNotEmpty()) { "refactoringSteps must not be empty" }
    }
}

@Serializable
data class AiSuggestionsEnvelope(
    val status: AiAdvisoryStatus,
    val provider: String,
    val model: String? = null,
    val latencyMs: Long = 0,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val injectionAttemptsNeutralized: Int = 0,
    val notice: String,
    val suggestions: List<AiRemediationSuggestion> = emptyList(),
) {
    companion object {
        fun disabled(provider: String = "none"): AiSuggestionsEnvelope = AiSuggestionsEnvelope(
            status = AiAdvisoryStatus.DISABLED,
            provider = provider,
            model = null,
            notice = "AI remediation advisor is disabled (AI_PROVIDER=none). Verified findings above are 100% deterministic.",
            suggestions = emptyList(),
        )

        fun skippedNoViolations(provider: String, model: String?): AiSuggestionsEnvelope = AiSuggestionsEnvelope(
            status = AiAdvisoryStatus.SKIPPED_NO_VIOLATIONS,
            provider = provider,
            model = model,
            notice = "Zero verified architectural violations detected; AI remediation skipped.",
            suggestions = emptyList(),
        )
    }
}

/**
 * Top-level immutable audit report enforcing type-level separation between
 * [verifiedFindings] (deterministic ground truth) and [aiSuggestions] (advisory only).
 */
@Serializable
data class AuditReport(
    val scanId: String,
    val repositoryPath: String,
    val rulesetName: String,
    val createdAt: String,
    val graph: DependencyGraph,
    val analysis: GraphAnalysisSummary,
    val telemetry: ScanTelemetry,
    val verifiedFindings: VerifiedFindingsEnvelope,
    val aiSuggestions: AiSuggestionsEnvelope,
) {
    /**
     * Attaches AI advisory suggestions without any ability to alter [verifiedFindings],
     * [graph], or [analysis]. Suggestions referencing unknown violation IDs are dropped.
     */
    fun attachAdvisorySuggestions(envelope: AiSuggestionsEnvelope): AuditReport {
        val validIds = verifiedFindings.violations.map { it.id }.toSet()
        val filtered = envelope.suggestions.filter { it.targetViolationId in validIds }
        val sanitizedEnvelope = envelope.copy(suggestions = filtered)
        return copy(aiSuggestions = sanitizedEnvelope)
    }

    fun toSnapshot(): ScanSnapshot = ScanSnapshot(
        scanId = scanId,
        repositoryPath = repositoryPath,
        rulesetName = rulesetName,
        createdAt = createdAt,
        deterministicDigest = verifiedFindings.deterministicDigest,
        graph = graph,
        analysis = analysis,
        verifiedFindings = verifiedFindings,
        telemetry = telemetry,
    )
}
