package dev.lab.auditor.infrastructure

import dev.lab.auditor.application.AuditUseCase
import dev.lab.auditor.application.ExportFormat
import dev.lab.auditor.application.ReportExporter
import dev.lab.auditor.application.RepositoryScanner
import dev.lab.auditor.domain.RuleLoader
import dev.lab.auditor.domain.ScanDiff
import dev.lab.auditor.infrastructure.ai.HttpAiAdvisor
import dev.lab.auditor.infrastructure.api.configureAuditorServer
import dev.lab.auditor.infrastructure.persistence.SqliteScanRepository
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private val cliJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

fun main(args: Array<String>) {
    val exitCode = runCli(args)
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

fun runCli(args: Array<String>): Int {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        printUsage()
        return 0
    }

    return try {
        when (args[0].lowercase()) {
            "audit" -> handleAudit(args.drop(1))
            "diff" -> handleDiff(args.drop(1))
            "export" -> handleExport(args.drop(1))
            "serve" -> handleServe()
            else -> {
                System.err.println("Unknown command '${args[0]}'.")
                printUsage()
                2
            }
        }
    } catch (ex: Exception) {
        System.err.println("Error: ${ex.message}")
        1
    }
}

private fun handleAudit(args: List<String>): Int {
    if (args.isEmpty()) {
        throw IllegalArgumentException("audit requires <path> argument")
    }
    val targetPath = Path.of(args[0])
    val opts = parseOptions(args.drop(1))
    val baseConfig = AuditorConfig.fromEnv()
    val config = baseConfig.copy(
        dbPath = opts["--db"] ?: baseConfig.dbPath,
    )
    config.validate(requireServerApiKey = false)

    val ruleSet = opts["--rules"]?.let { RuleLoader.loadFromFile(Path.of(it)) }
        ?: RuleLoader.defaultHexagonalRuleSet()
    val format = ExportFormat.parse(opts["--format"] ?: "json")
    val includeAi = !opts.containsKey("--no-ai")

    val metrics = AuditorMetricsRegistry()
    val repo = SqliteScanRepository(config.dbPath)
    val scanner = RepositoryScanner(repo, config.toScannerLimits())
    val advisor = HttpAiAdvisor.fromConfig(config, metrics)

    try {
        val useCase = AuditUseCase(scanner, repo, advisor, repo)
        val report = runBlocking {
            useCase.runAudit(
                repositoryPath = targetPath,
                ruleSet = ruleSet,
                includeAiAdvice = includeAi,
                actor = "cli",
            )
        }
        val output = ReportExporter.export(report, format)
        val outPath = opts["--out"]
        if (outPath != null) {
            val file = Path.of(outPath)
            file.parent?.let { Files.createDirectories(it) }
            Files.writeString(file, output)
            System.err.println(
                "Audit complete: scanId=${report.scanId} violations=${report.verifiedFindings.violationCount} cycles=${report.verifiedFindings.cycleCount} written=$outPath",
            )
        } else {
            println(output)
        }
        return 0
    } finally {
        if (advisor is Closeable) advisor.close()
    }
}

private fun handleDiff(args: List<String>): Int {
    if (args.size < 2) {
        throw IllegalArgumentException("diff requires <baseScanId> <headScanId>")
    }
    val baseId = args[0]
    val headId = args[1]
    val opts = parseOptions(args.drop(2))
    val baseConfig = AuditorConfig.fromEnv()
    val dbPath = opts["--db"] ?: baseConfig.dbPath

    val repo = SqliteScanRepository(dbPath)
    val scanner = RepositoryScanner(repo, baseConfig.toScannerLimits())
    val useCase = AuditUseCase(scanner, repo, dev.lab.auditor.infrastructure.ai.DisabledAiAdvisor(), repo)
    val diff = useCase.diffScans(baseId, headId)
    println(cliJson.encodeToString(ScanDiff.serializer(), diff))
    return if (diff.regressionDetected) 3 else 0
}

private fun handleExport(args: List<String>): Int {
    if (args.isEmpty()) {
        throw IllegalArgumentException("export requires <scanId>")
    }
    val scanId = args[0]
    val opts = parseOptions(args.drop(1))
    val baseConfig = AuditorConfig.fromEnv()
    val dbPath = opts["--db"] ?: baseConfig.dbPath
    val format = ExportFormat.parse(opts["--format"] ?: "json")

    val repo = SqliteScanRepository(dbPath)
    val scanner = RepositoryScanner(repo, baseConfig.toScannerLimits())
    val useCase = AuditUseCase(scanner, repo, dev.lab.auditor.infrastructure.ai.DisabledAiAdvisor(), repo)
    val report = useCase.getScan(scanId)
    val output = ReportExporter.export(report, format)
    val outPath = opts["--out"]
    if (outPath != null) {
        val file = Path.of(outPath)
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, output)
    } else {
        println(output)
    }
    return 0
}

private fun handleServe(): Int {
    val config = AuditorConfig.fromEnv()
    config.validate(requireServerApiKey = true)

    val metrics = AuditorMetricsRegistry()
    val repo = SqliteScanRepository(config.dbPath)
    val scanner = RepositoryScanner(repo, config.toScannerLimits())
    val advisor = HttpAiAdvisor.fromConfig(config, metrics)
    val useCase = AuditUseCase(scanner, repo, advisor, repo)

    val server = embeddedServer(Netty, host = config.host, port = config.port) {
        configureAuditorServer(
            config = config,
            auditUseCase = useCase,
            scanner = scanner,
            repository = repo,
            metrics = metrics,
        )
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(gracePeriodMillis = 1_500, timeoutMillis = 5_000)
            if (advisor is Closeable) advisor.close()
        },
    )

    server.start(wait = true)
    return 0
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--no-ai" -> {
                map[arg] = "true"
                i++
            }
            arg.startsWith("--") && i + 1 < args.size -> {
                map[arg] = args[i + 1]
                i += 2
            }
            else -> throw IllegalArgumentException("Unexpected or incomplete argument '$arg'")
        }
    }
    return map
}

private fun printUsage() {
    println(
        """
        Software Architecture Auditor (Deterministic Graph & Rule Engine + Advisory AI)

        Usage:
          arch-auditor audit <path> [--rules <file>] [--format json|markdown|sarif|mermaid] [--out <file>] [--db <path>] [--no-ai]
          arch-auditor diff <baseScanId> <headScanId> [--db <path>]
          arch-auditor export <scanId> [--format json|markdown|sarif|mermaid] [--out <file>] [--db <path>]
          arch-auditor serve
        """.trimIndent(),
    )
}
