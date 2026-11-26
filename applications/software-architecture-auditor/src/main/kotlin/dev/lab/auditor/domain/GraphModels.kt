package dev.lab.auditor.domain

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
enum class Language {
    KOTLIN,
    JAVA,
    TYPESCRIPT,
    PYTHON,
    GO,
}

@Serializable
data class ParseWarning(
    val file: String,
    val line: Int,
    val message: String,
)

@Serializable
data class SourceFileNode(
    val path: String,
    val language: Language,
    val module: String,
    val packageName: String,
    val sha256: String,
    val lineCount: Int,
    val parseWarnings: List<ParseWarning> = emptyList(),
)

@Serializable
data class DependencyEdge(
    val sourceNode: String,
    val sourceModule: String,
    val targetModuleOrPackage: String,
    val targetModule: String,
    val importSymbol: String,
    val lineNumber: Int,
)

@Serializable
data class ModuleDependencyMatrixEntry(
    val fromModule: String,
    val toModule: String,
    val edgeCount: Int,
)

@Serializable
data class DependencyGraph(
    val nodes: List<SourceFileNode>,
    val edges: List<DependencyEdge>,
    val modules: List<String>,
    val moduleEdges: List<ModuleDependencyMatrixEntry>,
    val deterministicDigest: String,
) {
    fun moduleAdjacency(): Map<String, Set<String>> {
        val adj = sortedMapOf<String, MutableSet<String>>()
        modules.forEach { adj[it] = sortedSetOf() }
        val knownModules = modules.toSet()
        for (edge in edges) {
            if (edge.sourceModule in knownModules && edge.targetModule in knownModules) {
                adj.getOrPut(edge.sourceModule) { sortedSetOf() }.add(edge.targetModule)
            }
        }
        return adj.mapValues { it.value.toSet() }
    }

    fun packageAdjacency(): Map<String, Set<String>> {
        val knownPackages = nodes.map { it.packageName }.filter { it.isNotBlank() }.toSortedSet()
        val nodeToPackage = nodes.associate { it.path to it.packageName }
        val adj = sortedMapOf<String, MutableSet<String>>()
        knownPackages.forEach { adj[it] = sortedSetOf() }

        for (edge in edges) {
            val srcPkg = nodeToPackage[edge.sourceNode].orEmpty()
            if (srcPkg.isBlank()) continue
            val targetPkg = resolveTargetPackage(edge.targetModuleOrPackage, knownPackages)
            if (targetPkg != null) {
                adj.getOrPut(srcPkg) { sortedSetOf() }.add(targetPkg)
            }
        }
        return adj.mapValues { it.value.toSet() }
    }

    private fun resolveTargetPackage(target: String, knownPackages: Set<String>): String? {
        if (target in knownPackages) return target
        val lastDot = target.lastIndexOf('.')
        if (lastDot > 0) {
            val prefix = target.substring(0, lastDot)
            if (prefix in knownPackages) return prefix
        }
        return knownPackages
            .filter { target.startsWith("$it.") || target.startsWith("$it/") }
            .maxByOrNull { it.length }
    }

    companion object {
        fun canonical(
            rawNodes: List<SourceFileNode>,
            rawEdges: List<DependencyEdge>,
        ): DependencyGraph {
            val sortedNodes = rawNodes
                .map { node ->
                    node.copy(
                        parseWarnings = node.parseWarnings.sortedWith(
                            compareBy<ParseWarning> { it.file }.thenBy { it.line }.thenBy { it.message },
                        ),
                    )
                }
                .distinctBy { it.path }
                .sortedBy { it.path }

            val sortedEdges = rawEdges
                .distinct()
                .sortedWith(
                    compareBy<DependencyEdge> { it.sourceNode }
                        .thenBy { it.sourceModule }
                        .thenBy { it.targetModule }
                        .thenBy { it.targetModuleOrPackage }
                        .thenBy { it.importSymbol }
                        .thenBy { it.lineNumber },
                )

            val modules = sortedNodes.map { it.module }.filter { it.isNotBlank() }.distinct().sorted()
            val knownModules = modules.toSet()

            val matrix = sortedEdges
                .filter { it.sourceModule in knownModules && it.targetModule in knownModules && it.sourceModule != it.targetModule }
                .groupingBy { it.sourceModule to it.targetModule }
                .eachCount()
                .entries
                .map { (pair, count) ->
                    ModuleDependencyMatrixEntry(
                        fromModule = pair.first,
                        toModule = pair.second,
                        edgeCount = count,
                    )
                }
                .sortedWith(compareBy<ModuleDependencyMatrixEntry> { it.fromModule }.thenBy { it.toModule })

            val digest = computeDigest(sortedNodes, sortedEdges, modules)
            return DependencyGraph(
                nodes = sortedNodes,
                edges = sortedEdges,
                modules = modules,
                moduleEdges = matrix,
                deterministicDigest = digest,
            )
        }

        private fun computeDigest(
            nodes: List<SourceFileNode>,
            edges: List<DependencyEdge>,
            modules: List<String>,
        ): String {
            val sb = StringBuilder()
            sb.append("MODULES:")
            modules.forEach { sb.append(it).append(';') }
            sb.append("\nNODES:\n")
            nodes.forEach { n ->
                sb.append(n.path).append('|')
                    .append(n.language).append('|')
                    .append(n.module).append('|')
                    .append(n.packageName).append('|')
                    .append(n.sha256).append('|')
                    .append(n.lineCount).append('\n')
            }
            sb.append("EDGES:\n")
            edges.forEach { e ->
                sb.append(e.sourceNode).append('|')
                    .append(e.sourceModule).append('|')
                    .append(e.targetModule).append('|')
                    .append(e.targetModuleOrPackage).append('|')
                    .append(e.importSymbol).append('|')
                    .append(e.lineNumber).append('\n')
            }
            return sha256Hex(sb.toString())
        }
    }
}

fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
