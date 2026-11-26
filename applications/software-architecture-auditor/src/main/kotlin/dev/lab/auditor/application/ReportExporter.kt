package dev.lab.auditor.application

import dev.lab.auditor.domain.AuditReport
import dev.lab.auditor.domain.RuleType
import dev.lab.auditor.domain.Severity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ExportFormat {
    JSON,
    MARKDOWN,
    SARIF,
    MERMAID;

    companion object {
        fun parse(raw: String): ExportFormat = when (raw.trim().lowercase()) {
            "json" -> JSON
            "md", "markdown" -> MARKDOWN
            "sarif" -> SARIF
            "mmd", "mermaid" -> MERMAID
            else -> throw IllegalArgumentException("Unsupported export format '$raw'. Expected: json, markdown, sarif, mermaid")
        }
    }
}

object ReportExporter {
    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun export(report: AuditReport, format: ExportFormat): String = when (format) {
        ExportFormat.JSON -> exportJson(report)
        ExportFormat.MARKDOWN -> exportMarkdown(report)
        ExportFormat.SARIF -> exportSarif(report)
        ExportFormat.MERMAID -> exportMermaid(report)
    }

    fun contentType(format: ExportFormat): String = when (format) {
        ExportFormat.JSON -> "application/json"
        ExportFormat.MARKDOWN -> "text/markdown; charset=utf-8"
        ExportFormat.SARIF -> "application/sarif+json"
        ExportFormat.MERMAID -> "text/plain; charset=utf-8"
    }

    fun exportJson(report: AuditReport): String =
        prettyJson.encodeToString(AuditReport.serializer(), report)

    fun exportMermaid(report: AuditReport): String {
        val sb = StringBuilder()
        sb.appendLine("graph LR")

        if (report.graph.modules.isEmpty()) {
            sb.appendLine("    empty[\"No modules discovered\"]")
            return sb.toString()
        }

        val cycleEdgePairs = mutableSetOf<Pair<String, String>>()
        for (cycle in report.analysis.cycles) {
            for (idx in cycle.nodes.indices) {
                val from = cycle.nodes[idx]
                val to = cycle.nodes[(idx + 1) % cycle.nodes.size]
                cycleEdgePairs.add(from to to)
            }
        }

        val violationEdgePairs = report.verifiedFindings.violations
            .filter { it.ruleType == RuleType.FORBIDDEN_DEPENDENCY || it.ruleType == RuleType.ALLOWED_DEPENDENCIES_ONLY }
            .map { it.source to it.target }
            .toSet()

        val couplingByModule = report.analysis.couplingMetrics.associateBy { it.module }

        for (module in report.graph.modules) {
            val id = mermaidId(module)
            val m = couplingByModule[module]
            val label = if (m != null) {
                "$module\\nCa=${m.afferentCoupling} Ce=${m.efferentCoupling} I=${m.instability}"
            } else {
                module
            }
            sb.appendLine("    $id[\"$label\"]")
        }

        var edgeIndex = 0
        val cycleLinkIndices = mutableListOf<Int>()
        val violationLinkIndices = mutableListOf<Int>()

        for (edge in report.graph.moduleEdges) {
            val pair = edge.fromModule to edge.toModule
            val fromId = mermaidId(edge.fromModule)
            val toId = mermaidId(edge.toModule)
            val isCycle = pair in cycleEdgePairs
            val isViolation = pair in violationEdgePairs

            when {
                isCycle -> {
                    sb.appendLine("    $fromId -. \"${edge.edgeCount} (CYCLE)\" .-> $toId")
                    cycleLinkIndices.add(edgeIndex)
                }
                isViolation -> {
                    sb.appendLine("    $fromId -- \"${edge.edgeCount} (VIOLATION)\" --> $toId")
                    violationLinkIndices.add(edgeIndex)
                }
                else -> {
                    sb.appendLine("    $fromId -- \"${edge.edgeCount}\" --> $toId")
                }
            }
            edgeIndex++
        }

        if (cycleLinkIndices.isNotEmpty()) {
            sb.appendLine("    linkStyle ${cycleLinkIndices.joinToString(",")} stroke:#d93025,stroke-width:3px,color:#d93025")
        }
        if (violationLinkIndices.isNotEmpty()) {
            sb.appendLine("    linkStyle ${violationLinkIndices.joinToString(",")} stroke:#e65100,stroke-width:2.5px,color:#e65100")
        }

        return sb.toString()
    }

    fun exportMarkdown(report: AuditReport): String = buildString {
        appendLine("# Software Architecture Audit Report")
        appendLine()
        appendLine("- **Scan ID:** `${report.scanId}`")
        appendLine("- **Ruleset:** `${report.rulesetName}`")
        appendLine("- **Deterministic Digest:** `${report.verifiedFindings.deterministicDigest}`")
        appendLine("- **Files Scanned:** ${report.telemetry.filesScanned} (cache hits: ${report.telemetry.cacheHits}, misses: ${report.telemetry.cacheMisses}, parse warnings: ${report.telemetry.parseWarningCount})")
        appendLine("- **Verified Violations:** ${report.verifiedFindings.violationCount}")
        appendLine("- **Module Cycles:** ${report.verifiedFindings.cycleCount}")
        appendLine()

        appendLine("## 1. Verified Architectural Findings (Deterministic)")
        appendLine()
        if (report.verifiedFindings.violations.isEmpty()) {
            appendLine("No architectural violations detected.")
        } else {
            appendLine("| Severity | Rule ID | Type | Source | Target | Evidence | Message |")
            appendLine("|---|---|---|---|---|---|---|")
            for (v in report.verifiedFindings.violations) {
                val safeMsg = v.message.replace("|", "\\|")
                appendLine("| ${v.severity} | `${v.ruleId}` | `${v.ruleType}` | `${v.source}` | `${v.target}` | `${v.evidenceFile}:${v.evidenceLine}` | $safeMsg |")
            }
        }
        appendLine()

        appendLine("## 2. Module Coupling Metrics (Robert C. Martin)")
        appendLine()
        if (report.analysis.couplingMetrics.isEmpty()) {
            appendLine("No modules found.")
        } else {
            appendLine("| Module | Ca (Fan-In) | Ce (Fan-Out) | Instability (I) |")
            appendLine("|---|---:|---:|---:|")
            for (m in report.analysis.couplingMetrics) {
                appendLine("| `${m.module}` | ${m.afferentCoupling} | ${m.efferentCoupling} | ${m.instability} |")
            }
        }
        appendLine()

        appendLine("## 3. Dependency Graph (Mermaid)")
        appendLine()
        appendLine("```mermaid")
        append(exportMermaid(report).trimEnd())
        appendLine()
        appendLine("```")
        appendLine()

        appendLine("## 4. AI Advisory Remediation (Non-Authoritative)")
        appendLine()
        appendLine("> **Status:** `${report.aiSuggestions.status}` | **Provider:** `${report.aiSuggestions.provider}` | ${report.aiSuggestions.notice}")
        appendLine()
        if (report.aiSuggestions.suggestions.isNotEmpty()) {
            for (s in report.aiSuggestions.suggestions) {
                appendLine("### ${s.title} (`${s.targetViolationId}`)")
                appendLine("- **Rationale:** ${s.rationale}")
                appendLine("- **Estimated Effort:** ${s.estimatedEffort}")
                appendLine("- **Steps:**")
                s.refactoringSteps.forEachIndexed { idx, step ->
                    appendLine("  ${idx + 1}. $step")
                }
                appendLine()
            }
        }
    }

    // --- SARIF 2.1.0 ---

    @Serializable
    private data class SarifLog(
        @SerialName("\$schema") val schema: String = "https://json.schemastore.org/sarif-2.1.0.json",
        val version: String = "2.1.0",
        val runs: List<SarifRun>,
    )

    @Serializable
    private data class SarifRun(
        val tool: SarifTool,
        val results: List<SarifResult>,
    )

    @Serializable
    private data class SarifTool(
        val driver: SarifDriver,
    )

    @Serializable
    private data class SarifDriver(
        val name: String,
        val version: String,
        val informationUri: String,
        val rules: List<SarifReportingDescriptor>,
    )

    @Serializable
    private data class SarifReportingDescriptor(
        val id: String,
        val shortDescription: SarifMessage,
    )

    @Serializable
    private data class SarifResult(
        val ruleId: String,
        val level: String,
        val message: SarifMessage,
        val locations: List<SarifLocation>,
        val partialFingerprints: Map<String, String>,
    )

    @Serializable
    private data class SarifMessage(
        val text: String,
    )

    @Serializable
    private data class SarifLocation(
        val physicalLocation: SarifPhysicalLocation,
    )

    @Serializable
    private data class SarifPhysicalLocation(
        val artifactLocation: SarifArtifactLocation,
        val region: SarifRegion,
    )

    @Serializable
    private data class SarifArtifactLocation(
        val uri: String,
    )

    @Serializable
    private data class SarifRegion(
        val startLine: Int,
    )

    fun exportSarif(report: AuditReport): String {
        val ruleDescriptors = report.verifiedFindings.violations
            .distinctBy { it.ruleId }
            .map { v ->
                SarifReportingDescriptor(
                    id = v.ruleId,
                    shortDescription = SarifMessage("${v.ruleType}: architectural governance rule"),
                )
            }

        val results = report.verifiedFindings.violations.map { v ->
            val level = when (v.severity) {
                Severity.CRITICAL, Severity.HIGH -> "error"
                Severity.MEDIUM -> "warning"
                Severity.LOW -> "note"
            }
            SarifResult(
                ruleId = v.ruleId,
                level = level,
                message = SarifMessage(v.message),
                locations = listOf(
                    SarifLocation(
                        physicalLocation = SarifPhysicalLocation(
                            artifactLocation = SarifArtifactLocation(uri = v.evidenceFile),
                            region = SarifRegion(startLine = v.evidenceLine.coerceAtLeast(1)),
                        ),
                    ),
                ),
                partialFingerprints = mapOf(
                    "primaryLocationLineHash" to v.id,
                    "deterministicDigest" to report.verifiedFindings.deterministicDigest,
                ),
            )
        }

        val log = SarifLog(
            runs = listOf(
                SarifRun(
                    tool = SarifTool(
                        driver = SarifDriver(
                            name = "software-architecture-auditor",
                            version = "1.0.0",
                            informationUri = "https://github.com/ai-learning-lab/software-architecture-auditor",
                            rules = ruleDescriptors,
                        ),
                    ),
                    results = results,
                ),
            ),
        )
        return prettyJson.encodeToString(SarifLog.serializer(), log)
    }

    private fun mermaidId(name: String): String =
        "mod_" + name.replace(Regex("[^A-Za-z0-9_]"), "_")
}
