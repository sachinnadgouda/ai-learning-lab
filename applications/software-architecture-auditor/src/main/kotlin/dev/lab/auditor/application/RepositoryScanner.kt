package dev.lab.auditor.application

import dev.lab.auditor.domain.DependencyEdge
import dev.lab.auditor.domain.DependencyGraph
import dev.lab.auditor.domain.Language
import dev.lab.auditor.domain.ParseWarning
import dev.lab.auditor.domain.ScanTelemetry
import dev.lab.auditor.domain.SourceFileNode
import dev.lab.auditor.domain.sha256Hex
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class ScannerLimits(
    val maxFiles: Int = 5_000,
    val maxFileBytes: Long = 1_048_576L,
    val maxDepth: Int = 20,
    val ignoredDirectories: Set<String> = DEFAULT_IGNORED_DIRS,
    val rejectSymlinkEscapes: Boolean = true,
) {
    init {
        require(maxFiles > 0) { "maxFiles must be positive" }
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
    }

    companion object {
        val DEFAULT_IGNORED_DIRS = setOf(
            ".git",
            "node_modules",
            "build",
            "target",
            ".venv",
            "venv",
            "dist",
            "out",
            ".gradle",
            ".idea",
            "__pycache__",
            ".next",
        )
    }
}

class PathTraversalException(message: String) : SecurityException(message)

data class ScanOutput(
    val graph: DependencyGraph,
    val telemetry: ScanTelemetry,
    val securityWarnings: List<ParseWarning> = emptyList(),
)

/**
 * Bounded, incremental, security-hardened repository scanner.
 * - Canonicalizes scan root and blocks symlink/path-traversal escapes.
 * - Uses SHA-256 content hashes with [FileCachePort] to skip re-parsing unchanged files.
 * - Resolves polyglot imports (Kotlin, Java, TypeScript/JS, Python, Go) into a canonical [DependencyGraph].
 */
class RepositoryScanner(
    private val fileCache: FileCachePort,
    private val limits: ScannerLimits = ScannerLimits(),
) {

    fun validateAllowedRoot(requestedPath: Path, allowedRoots: List<Path>): Path {
        val normalized = requestedPath.toAbsolutePath().normalize()
        val canonicalAllowed = allowedRoots
            .filter { Files.exists(it) }
            .map { it.toAbsolutePath().normalize().toRealPath() }

        if (!Files.exists(normalized)) {
            if (allowedRoots.isNotEmpty() && canonicalAllowed.none { allowed -> normalized.startsWith(allowed) }) {
                throw PathTraversalException(
                    "Requested scan path '$requestedPath' is outside configured AUDITOR_ALLOWED_ROOTS",
                )
            }
            throw IllegalArgumentException("Repository path does not exist: $requestedPath")
        }
        val realPath = normalized.toRealPath()
        if (!Files.isDirectory(realPath)) {
            throw IllegalArgumentException("Repository path is not a directory: $requestedPath")
        }
        if (allowedRoots.isNotEmpty()) {
            val permitted = canonicalAllowed.any { allowed -> realPath.startsWith(allowed) }
            if (!permitted) {
                throw PathTraversalException(
                    "Requested scan path '$requestedPath' is outside configured AUDITOR_ALLOWED_ROOTS",
                )
            }
        }
        return realPath
    }

    fun scan(rootPath: Path, strictSymlinkRejection: Boolean = false): ScanOutput {
        val startNanos = System.nanoTime()
        val normalizedRoot = rootPath.toAbsolutePath().normalize()
        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            throw IllegalArgumentException("Scan root must be an existing directory: $rootPath")
        }
        val canonicalRoot = normalizedRoot.toRealPath()

        val discoveredFiles = mutableListOf<Path>()
        val securityWarnings = mutableListOf<ParseWarning>()
        var filesSkipped = 0
        var truncatedByLimit = false

        Files.walkFileTree(
            canonicalRoot,
            emptySet<FileVisitOption>(),
            limits.maxDepth,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == canonicalRoot) return FileVisitResult.CONTINUE
                    val name = dir.fileName?.toString().orEmpty()
                    if (name in limits.ignoredDirectories) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (Files.isSymbolicLink(dir)) {
                        val target = runCatching { dir.toRealPath() }.getOrNull()
                        if (target == null || !target.startsWith(canonicalRoot)) {
                            val rel = canonicalRoot.relativize(dir).toString().replace('\\', '/')
                            if (strictSymlinkRejection) {
                                throw PathTraversalException("Symlink directory '$rel' escapes scan root")
                            }
                            securityWarnings.add(ParseWarning(rel, 1, "Rejected symlink directory escaping scan root"))
                            filesSkipped++
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relPath = canonicalRoot.relativize(file).toString().replace('\\', '/')
                    if (Files.isSymbolicLink(file)) {
                        val realTarget = runCatching { file.toRealPath() }.getOrNull()
                        if (realTarget == null || !realTarget.startsWith(canonicalRoot)) {
                            if (strictSymlinkRejection) {
                                throw PathTraversalException("Symlink file '$relPath' escapes scan root")
                            }
                            securityWarnings.add(ParseWarning(relPath, 1, "Rejected symlink file escaping scan root"))
                            filesSkipped++
                            return FileVisitResult.CONTINUE
                        }
                    }

                    if (LanguageParsers.detectLanguage(relPath) == null) {
                        return FileVisitResult.CONTINUE
                    }

                    val size = runCatching { Files.size(file) }.getOrDefault(Long.MAX_VALUE)
                    if (size > limits.maxFileBytes) {
                        securityWarnings.add(ParseWarning(relPath, 1, "Skipped file exceeding maxFileBytes (${limits.maxFileBytes})"))
                        filesSkipped++
                        return FileVisitResult.CONTINUE
                    }

                    if (discoveredFiles.size >= limits.maxFiles) {
                        truncatedByLimit = true
                        filesSkipped++
                        return FileVisitResult.TERMINATE
                    }

                    discoveredFiles.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    filesSkipped++
                    return FileVisitResult.CONTINUE
                }
            },
        )

        discoveredFiles.sortBy { canonicalRoot.relativize(it).toString().replace('\\', '/') }

        var cacheHits = 0
        var cacheMisses = 0

        data class ParsedFileRecord(
            val relativePath: String,
            val language: Language,
            val module: String,
            val packageName: String,
            val sha256: String,
            val lineCount: Int,
            val imports: List<ExtractedImport>,
            val warnings: List<ParseWarning>,
        )

        val parsedRecords = mutableListOf<ParsedFileRecord>()

        for (file in discoveredFiles) {
            val relPath = canonicalRoot.relativize(file).toString().replace('\\', '/')
            val language = LanguageParsers.detectLanguage(relPath) ?: continue
            val bytes = try {
                Files.readAllBytes(file)
            } catch (_: IOException) {
                filesSkipped++
                continue
            }
            val hash = sha256Hex(bytes)
            val cached = fileCache.get(relPath, hash)
            val parsed = if (cached != null) {
                cacheHits++
                cached
            } else {
                cacheMisses++
                val content = String(bytes, Charsets.UTF_8)
                val result = LanguageParsers.parse(relPath, language, content)
                val entry = CachedFileParse(
                    relativePath = relPath,
                    sha256 = hash,
                    language = language,
                    packageName = result.packageName,
                    lineCount = result.lineCount,
                    imports = result.imports,
                    parseWarnings = result.warnings,
                )
                fileCache.put(entry)
                entry
            }

            val module = inferModule(relPath, parsed.packageName)
            parsedRecords.add(
                ParsedFileRecord(
                    relativePath = relPath,
                    language = parsed.language,
                    module = module,
                    packageName = parsed.packageName,
                    sha256 = parsed.sha256,
                    lineCount = parsed.lineCount,
                    imports = parsed.imports,
                    warnings = parsed.parseWarnings,
                ),
            )
        }

        val knownModules = parsedRecords.map { it.module }.filter { it.isNotBlank() }.toSet()
        val packageToModule = parsedRecords
            .filter { it.packageName.isNotBlank() }
            .associate { it.packageName to it.module }

        val nodes = parsedRecords.map { rec ->
            SourceFileNode(
                path = rec.relativePath,
                language = rec.language,
                module = rec.module,
                packageName = rec.packageName,
                sha256 = rec.sha256,
                lineCount = rec.lineCount,
                parseWarnings = rec.warnings,
            )
        }

        val edges = mutableListOf<DependencyEdge>()
        for (rec in parsedRecords) {
            for (imp in rec.imports) {
                val targetModule = resolveTargetModule(
                    sourcePath = rec.relativePath,
                    language = rec.language,
                    rawTarget = imp.rawTarget,
                    knownModules = knownModules,
                    packageToModule = packageToModule,
                )
                edges.add(
                    DependencyEdge(
                        sourceNode = rec.relativePath,
                        sourceModule = rec.module,
                        targetModuleOrPackage = imp.rawTarget,
                        targetModule = targetModule,
                        importSymbol = imp.symbol,
                        lineNumber = imp.lineNumber,
                    ),
                )
            }
        }

        val graph = DependencyGraph.canonical(nodes, edges)
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
        val parseWarningCount = graph.nodes.sumOf { it.parseWarnings.size } + securityWarnings.size

        return ScanOutput(
            graph = graph,
            telemetry = ScanTelemetry(
                filesScanned = graph.nodes.size,
                filesSkipped = filesSkipped,
                cacheHits = cacheHits,
                cacheMisses = cacheMisses,
                parseWarningCount = parseWarningCount,
                durationMs = durationMs,
                truncatedByLimit = truncatedByLimit,
            ),
            securityWarnings = securityWarnings,
        )
    }

    internal fun inferModule(relativePath: String, packageName: String): String {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.size <= 1) {
            return packageName.substringAfterLast('.').ifBlank { "root" }
        }
        val first = segments[0]
        if (first in setOf("src", "app", "lib", "pkg", "internal", "cmd", "modules", "services")) {
            val rest = segments.drop(1)
            val withoutMainLang = if (rest.size >= 3 && rest[0] in setOf("main", "test") && rest[1] in setOf("kotlin", "java", "scala")) {
                rest.drop(2)
            } else {
                rest
            }
            if (withoutMainLang.size > 1) {
                // If Java/Kotlin package dirs like com/acme/domain/File.kt, pick the last directory before file
                val dirs = withoutMainLang.dropLast(1)
                if (dirs.size >= 3 && dirs[0] in setOf("com", "org", "dev", "io", "net")) {
                    return dirs.last()
                }
                return dirs.first()
            }
        }
        return first
    }

    internal fun resolveTargetModule(
        sourcePath: String,
        language: Language,
        rawTarget: String,
        knownModules: Set<String>,
        packageToModule: Map<String, String>,
    ): String {
        // 1. Relative TypeScript/JS path (./ or ../)
        if (language == Language.TYPESCRIPT && (rawTarget.startsWith("./") || rawTarget.startsWith("../"))) {
            val sourceDir = Path.of(sourcePath).parent ?: Path.of("")
            val resolved = sourceDir.resolve(rawTarget).normalize().toString().replace('\\', '/')
            val candidateMod = inferModule(resolved, "")
            if (candidateMod in knownModules) return candidateMod
            val segs = resolved.split('/').filter { it.isNotBlank() && it != ".." && it != "." }
            segs.firstOrNull { it in knownModules }?.let { return it }
            return ""
        }

        // 2. Relative Python import (.foo or ..bar)
        if (language == Language.PYTHON && rawTarget.startsWith(".")) {
            val dots = rawTarget.takeWhile { it == '.' }.length
            val rest = rawTarget.drop(dots)
            var currentDir = Path.of(sourcePath).parent
            repeat((dots - 1).coerceAtLeast(0)) {
                currentDir = currentDir?.parent
            }
            val relParts = rest.split('.').filter { it.isNotBlank() }
            val resolvedPath = if (currentDir != null) {
                (currentDir.toString().replace('\\', '/') + "/" + relParts.joinToString("/")).trimStart('/')
            } else {
                relParts.joinToString("/")
            }
            resolvedPath.split('/').firstOrNull { it in knownModules }?.let { return it }
            relParts.firstOrNull { it in knownModules }?.let { return it }
            return ""
        }

        // 3. Exact or prefix match on discovered package declarations
        packageToModule[rawTarget]?.let { return it }
        packageToModule.entries
            .filter { (pkg, _) -> rawTarget.startsWith("$pkg.") || rawTarget.startsWith("$pkg/") }
            .maxByOrNull { it.key.length }
            ?.let { return it.value }

        // 4. Match path/package segments against known internal module names
        val segments = rawTarget.split('.', '/').filter { it.isNotBlank() }
        // Prefer the most specific known module segment (from right to left for packages like com.app.infrastructure.db)
        for (seg in segments.asReversed()) {
            if (seg in knownModules) return seg
        }

        return ""
    }
}
