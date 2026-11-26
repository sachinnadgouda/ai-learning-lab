package dev.lab.auditor.domain

object RuleEngine {

    fun evaluate(
        graph: DependencyGraph,
        analysis: GraphAnalysisSummary,
        ruleSet: RuleSet,
    ): List<ArchitectureViolation> {
        RuleValidator.validate(ruleSet)

        val violations = mutableListOf<ArchitectureViolation>()
        val knownModules = graph.modules.toSet()

        for (rule in ruleSet.rules) {
            when (rule.type) {
                RuleType.FORBIDDEN_DEPENDENCY -> evaluateForbiddenDependency(rule, graph, violations)
                RuleType.ALLOWED_DEPENDENCIES_ONLY -> evaluateAllowedOnly(rule, graph, knownModules, violations)
                RuleType.NO_CYCLES -> evaluateNoCycles(rule, graph, analysis, violations)
                RuleType.MAX_EFFERENT_COUPLING -> evaluateMaxEfferentCoupling(rule, graph, analysis, violations)
                RuleType.MAX_INSTABILITY -> evaluateMaxInstability(rule, graph, analysis, violations)
            }
        }

        return violations
            .distinctBy { it.id }
            .sortedWith(
                compareBy<ArchitectureViolation> { it.severity.ordinal }
                    .thenBy { it.ruleId }
                    .thenBy { it.source }
                    .thenBy { it.target }
                    .thenBy { it.evidenceFile }
                    .thenBy { it.evidenceLine },
            )
    }

    private fun evaluateForbiddenDependency(
        rule: ArchitectureRule,
        graph: DependencyGraph,
        out: MutableList<ArchitectureViolation>,
    ) {
        val srcRegex = PatternMatcher.compile(rule.sourcePattern!!)
        val tgtRegex = PatternMatcher.compile(rule.targetPattern!!)

        for (edge in graph.edges) {
            if (edge.sourceModule == edge.targetModule) continue
            val sourceMatches = srcRegex.matches(edge.sourceModule) || srcRegex.matches(edge.sourceNode)
            val targetMatches = tgtRegex.matches(edge.targetModule) || tgtRegex.matches(edge.targetModuleOrPackage)
            if (sourceMatches && targetMatches) {
                out.add(
                    violation(
                        rule = rule,
                        source = edge.sourceModule,
                        target = edge.targetModule.ifBlank { edge.targetModuleOrPackage },
                        evidenceFile = edge.sourceNode,
                        evidenceLine = edge.lineNumber,
                        message = "${rule.name}: '${edge.sourceModule}' illegally imports '${edge.importSymbol}' (module '${edge.targetModule}')",
                    ),
                )
            }
        }
    }

    private fun evaluateAllowedOnly(
        rule: ArchitectureRule,
        graph: DependencyGraph,
        knownModules: Set<String>,
        out: MutableList<ArchitectureViolation>,
    ) {
        val srcRegex = PatternMatcher.compile(rule.sourcePattern!!)
        val allowedRegexes = rule.allowedTargets.map { PatternMatcher.compile(it) }

        for (edge in graph.edges) {
            if (edge.targetModule !in knownModules || edge.sourceModule == edge.targetModule) continue
            val sourceMatches = srcRegex.matches(edge.sourceModule) || srcRegex.matches(edge.sourceNode)
            if (!sourceMatches) continue

            val allowed = allowedRegexes.any { r ->
                r.matches(edge.targetModule) || r.matches(edge.targetModuleOrPackage)
            }
            if (!allowed) {
                out.add(
                    violation(
                        rule = rule,
                        source = edge.sourceModule,
                        target = edge.targetModule,
                        evidenceFile = edge.sourceNode,
                        evidenceLine = edge.lineNumber,
                        message = "${rule.name}: '${edge.sourceModule}' depends on disallowed internal module '${edge.targetModule}' via '${edge.importSymbol}'",
                    ),
                )
            }
        }
    }

    private fun evaluateNoCycles(
        rule: ArchitectureRule,
        graph: DependencyGraph,
        analysis: GraphAnalysisSummary,
        out: MutableList<ArchitectureViolation>,
    ) {
        val srcRegex = rule.sourcePattern?.let { PatternMatcher.compile(it) }
        val cycles = when (rule.granularity) {
            CycleGranularity.MODULE -> analysis.cycles
            CycleGranularity.PACKAGE -> analysis.packageCycles
        }

        for (cycle in cycles) {
            if (srcRegex != null && cycle.nodes.none { srcRegex.matches(it) }) {
                continue
            }
            val cyclePath = (cycle.nodes + cycle.nodes.first()).joinToString(" -> ")
            val firstEdge = findRepresentativeCycleEdge(graph, cycle)
            out.add(
                violation(
                    rule = rule,
                    source = cycle.nodes.first(),
                    target = if (cycle.nodes.size > 1) cycle.nodes[1] else cycle.nodes.first(),
                    evidenceFile = firstEdge?.sourceNode ?: "<graph:${cycle.granularity.name.lowercase()}>",
                    evidenceLine = firstEdge?.lineNumber ?: 1,
                    message = "${rule.name}: circular ${cycle.granularity.name.lowercase()} dependency detected ($cyclePath)",
                ),
            )
        }
    }

    private fun findRepresentativeCycleEdge(graph: DependencyGraph, cycle: DependencyCycle): DependencyEdge? {
        val nodeSet = cycle.nodes.toSet()
        return when (cycle.granularity) {
            CycleGranularity.MODULE -> {
                val first = cycle.nodes.first()
                graph.edges.firstOrNull {
                    it.sourceModule == first &&
                        it.targetModule in nodeSet &&
                        (cycle.nodes.size == 1 || it.targetModule != first)
                } ?: graph.edges.firstOrNull { it.sourceModule in nodeSet && it.targetModule in nodeSet }
            }
            CycleGranularity.PACKAGE -> {
                val nodePkg = graph.nodes.associate { it.path to it.packageName }
                graph.edges.firstOrNull { e ->
                    val sp = nodePkg[e.sourceNode]
                    sp in nodeSet && nodeSet.any { tp ->
                        e.targetModuleOrPackage == tp || e.targetModuleOrPackage.startsWith("$tp.")
                    }
                }
            }
        }
    }

    private fun evaluateMaxEfferentCoupling(
        rule: ArchitectureRule,
        graph: DependencyGraph,
        analysis: GraphAnalysisSummary,
        out: MutableList<ArchitectureViolation>,
    ) {
        val srcRegex = rule.sourcePattern?.let { PatternMatcher.compile(it) }
        val threshold = rule.maxThreshold!!.toInt()

        for (metric in analysis.couplingMetrics) {
            if (srcRegex != null && !srcRegex.matches(metric.module)) continue
            if (metric.efferentCoupling > threshold) {
                val evidence = graph.edges.firstOrNull { it.sourceModule == metric.module && it.targetModule != metric.module }
                out.add(
                    violation(
                        rule = rule,
                        source = metric.module,
                        target = metric.fanOutModules.joinToString(","),
                        evidenceFile = evidence?.sourceNode ?: "<module:${metric.module}>",
                        evidenceLine = evidence?.lineNumber ?: 1,
                        message = "${rule.name}: module '${metric.module}' has efferent coupling Ce=${metric.efferentCoupling} exceeding threshold $threshold (fan-out: ${metric.fanOutModules.joinToString(", ")})",
                    ),
                )
            }
        }
    }

    private fun evaluateMaxInstability(
        rule: ArchitectureRule,
        graph: DependencyGraph,
        analysis: GraphAnalysisSummary,
        out: MutableList<ArchitectureViolation>,
    ) {
        val srcRegex = rule.sourcePattern?.let { PatternMatcher.compile(it) }
        val threshold = rule.maxThreshold!!

        for (metric in analysis.couplingMetrics) {
            if (srcRegex != null && !srcRegex.matches(metric.module)) continue
            if (metric.instability > threshold) {
                val evidence = graph.edges.firstOrNull { it.sourceModule == metric.module && it.targetModule != metric.module }
                out.add(
                    violation(
                        rule = rule,
                        source = metric.module,
                        target = metric.fanOutModules.joinToString(","),
                        evidenceFile = evidence?.sourceNode ?: "<module:${metric.module}>",
                        evidenceLine = evidence?.lineNumber ?: 1,
                        message = "${rule.name}: module '${metric.module}' has instability I=${metric.instability} (Ca=${metric.afferentCoupling}, Ce=${metric.efferentCoupling}) exceeding threshold $threshold",
                    ),
                )
            }
        }
    }

    private fun violation(
        rule: ArchitectureRule,
        source: String,
        target: String,
        evidenceFile: String,
        evidenceLine: Int,
        message: String,
    ): ArchitectureViolation {
        val fingerprint = "${rule.id}|${rule.type}|$source|$target|$evidenceFile|$evidenceLine"
        val id = "viol-${sha256Hex(fingerprint).take(16)}"
        return ArchitectureViolation(
            id = id,
            ruleId = rule.id,
            ruleType = rule.type,
            severity = rule.severity,
            source = source,
            target = target,
            evidenceFile = evidenceFile,
            evidenceLine = evidenceLine,
            message = message,
            verified = true,
        )
    }
}
