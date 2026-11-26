package dev.lab.auditor.application

import dev.lab.auditor.domain.AiSuggestionsEnvelope
import dev.lab.auditor.domain.AuditReport
import dev.lab.auditor.domain.GraphAnalysisSummary
import dev.lab.auditor.domain.Language
import dev.lab.auditor.domain.ParseWarning
import dev.lab.auditor.domain.VerifiedFindingsEnvelope
import kotlinx.serialization.Serializable

@Serializable
data class ExtractedImport(
    val rawTarget: String,
    val symbol: String,
    val lineNumber: Int,
)

@Serializable
data class CachedFileParse(
    val relativePath: String,
    val sha256: String,
    val language: Language,
    val packageName: String,
    val lineCount: Int,
    val imports: List<ExtractedImport>,
    val parseWarnings: List<ParseWarning>,
)

@Serializable
data class ScanSummary(
    val scanId: String,
    val repositoryPath: String,
    val rulesetName: String,
    val createdAt: String,
    val deterministicDigest: String,
    val violationCount: Int,
    val cycleCount: Int,
    val filesScanned: Int,
)

data class ViolationSourceSnippet(
    val violationId: String,
    val filePath: String,
    val lineNumber: Int,
    val snippet: String,
)

data class AiRemediationRequest(
    val scanId: String,
    val repositoryName: String,
    val verifiedFindings: VerifiedFindingsEnvelope,
    val analysis: GraphAnalysisSummary,
    val codeSnippets: List<ViolationSourceSnippet>,
    val correlationId: String? = null,
)

interface ScanRepositoryPort {
    fun save(report: AuditReport)
    fun findById(scanId: String): AuditReport?
    fun listRecent(limit: Int = 50): List<ScanSummary>
    fun updateAiSuggestions(scanId: String, envelope: AiSuggestionsEnvelope): AuditReport?
    fun isHealthy(): Boolean
}

interface FileCachePort {
    fun get(relativePath: String, sha256: String): CachedFileParse?
    fun put(entry: CachedFileParse)
}

interface AiAdvisorPort {
    val providerName: String
    val modelName: String?
    suspend fun advise(request: AiRemediationRequest): AiSuggestionsEnvelope
}

interface AuditEventPort {
    fun record(
        eventType: String,
        correlationId: String?,
        actor: String,
        detail: Map<String, String>,
    )
}

class InMemoryFileCache : FileCachePort {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedFileParse>()

    override fun get(relativePath: String, sha256: String): CachedFileParse? =
        cache["$relativePath::$sha256"]

    override fun put(entry: CachedFileParse) {
        cache["${entry.relativePath}::${entry.sha256}"] = entry
    }
}

object NoOpAuditEventPort : AuditEventPort {
    override fun record(
        eventType: String,
        correlationId: String?,
        actor: String,
        detail: Map<String, String>,
    ) = Unit
}
