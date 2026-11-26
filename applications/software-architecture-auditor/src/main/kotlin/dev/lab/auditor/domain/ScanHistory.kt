package dev.lab.auditor.domain

import kotlinx.serialization.Serializable

@Serializable
data class ScanTelemetry(
    val filesScanned: Int,
    val filesSkipped: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val parseWarningCount: Int,
    val durationMs: Long,
    val truncatedByLimit: Boolean = false,
)

@Serializable
data class ScanSnapshot(
    val scanId: String,
    val repositoryPath: String,
    val rulesetName: String,
    val createdAt: String,
    val deterministicDigest: String,
    val graph: DependencyGraph,
    val analysis: GraphAnalysisSummary,
    val verifiedFindings: VerifiedFindingsEnvelope,
    val telemetry: ScanTelemetry,
)

@Serializable
data class CouplingDelta(
    val module: String,
    val previousCa: Int,
    val currentCa: Int,
    val previousCe: Int,
    val currentCe: Int,
    val previousInstability: Double,
    val currentInstability: Double,
)

@Serializable
data class ScanDiff(
    val baseScanId: String,
    val headScanId: String,
    val newViolations: List<ArchitectureViolation>,
    val resolvedViolations: List<ArchitectureViolation>,
    val unchangedViolations: List<ArchitectureViolation>,
    val couplingDeltas: List<CouplingDelta>,
    val cycleCountDelta: Int,
    val regressionDetected: Boolean,
)

object ScanDiffer {
    fun diff(base: ScanSnapshot, head: ScanSnapshot): ScanDiff {
        val baseById = base.verifiedFindings.violations.associateBy { it.id }
        val headById = head.verifiedFindings.violations.associateBy { it.id }

        val newViolations = head.verifiedFindings.violations
            .filter { it.id !in baseById }
        val resolvedViolations = base.verifiedFindings.violations
            .filter { it.id !in headById }
        val unchangedViolations = head.verifiedFindings.violations
            .filter { it.id in baseById }

        val baseCoupling = base.analysis.couplingMetrics.associateBy { it.module }
        val headCoupling = head.analysis.couplingMetrics.associateBy { it.module }
        val allModules = (baseCoupling.keys + headCoupling.keys).sorted()

        val couplingDeltas = allModules.mapNotNull { mod ->
            val prev = baseCoupling[mod]
            val curr = headCoupling[mod]
            val prevCa = prev?.afferentCoupling ?: 0
            val currCa = curr?.afferentCoupling ?: 0
            val prevCe = prev?.efferentCoupling ?: 0
            val currCe = curr?.efferentCoupling ?: 0
            val prevI = prev?.instability ?: 0.0
            val currI = curr?.instability ?: 0.0
            if (prevCa != currCa || prevCe != currCe || prevI != currI) {
                CouplingDelta(
                    module = mod,
                    previousCa = prevCa,
                    currentCa = currCa,
                    previousCe = prevCe,
                    currentCe = currCe,
                    previousInstability = prevI,
                    currentInstability = currI,
                )
            } else {
                null
            }
        }

        val cycleCountDelta = head.analysis.cycles.size - base.analysis.cycles.size
        val regressionDetected = newViolations.isNotEmpty() || cycleCountDelta > 0

        return ScanDiff(
            baseScanId = base.scanId,
            headScanId = head.scanId,
            newViolations = newViolations,
            resolvedViolations = resolvedViolations,
            unchangedViolations = unchangedViolations,
            couplingDeltas = couplingDeltas,
            cycleCountDelta = cycleCountDelta,
            regressionDetected = regressionDetected,
        )
    }
}
