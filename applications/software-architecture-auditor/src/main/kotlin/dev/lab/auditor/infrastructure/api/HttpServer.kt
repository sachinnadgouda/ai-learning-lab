package dev.lab.auditor.infrastructure.api

import dev.lab.auditor.application.AuditUseCase
import dev.lab.auditor.application.ExportFormat
import dev.lab.auditor.application.PathTraversalException
import dev.lab.auditor.application.ReportExporter
import dev.lab.auditor.application.RepositoryScanner
import dev.lab.auditor.application.ScanNotFoundException
import dev.lab.auditor.application.ScanRepositoryPort
import dev.lab.auditor.domain.RuleLoader
import dev.lab.auditor.domain.RuleValidationException
import dev.lab.auditor.infrastructure.AuditorConfig
import dev.lab.auditor.infrastructure.AuditorMetricsRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val correlationId: String,
)

@Serializable
data class CreateScanRequest(
    val repositoryPath: String,
    val rulesPath: String? = null,
    val rulesYaml: String? = null,
    val rulesJson: String? = null,
    val includeAiAdvice: Boolean = true,
)

@Serializable
data class HealthResponse(
    val status: String,
    val service: String = "software-architecture-auditor",
    val version: String = "1.0.0",
)

private val CorrelationIdKey = AttributeKey<String>("CorrelationId")
private val RequestStartNanosKey = AttributeKey<Long>("RequestStartNanos")

class UnauthorizedException(message: String) : RuntimeException(message)

fun Application.configureAuditorServer(
    config: AuditorConfig,
    auditUseCase: AuditUseCase,
    scanner: RepositoryScanner,
    repository: ScanRepositoryPort,
    metrics: AuditorMetricsRegistry,
) {
    val apiJson = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    install(ContentNegotiation) {
        json(apiJson)
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> !call.request.path().startsWith("/health") }
    }

    if (config.corsAllowedOrigins.isNotEmpty()) {
        install(CORS) {
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            allowHeader("X-API-Key")
            allowHeader("X-Correlation-ID")
            config.corsAllowedOrigins.forEach { origin ->
                allowHost(origin.removePrefix("https://").removePrefix("http://"))
            }
        }
    }

    install(StatusPages) {
        exception<UnauthorizedException> { call, cause ->
            call.respondProblem(
                status = HttpStatusCode.Unauthorized,
                type = "https://auditor.lab.dev/problems/unauthorized",
                title = "Authentication Required",
                detail = cause.message ?: "Missing or invalid X-API-Key",
                apiJson = apiJson,
            )
        }
        exception<PathTraversalException> { call, cause ->
            call.respondProblem(
                status = HttpStatusCode.Forbidden,
                type = "https://auditor.lab.dev/problems/path-not-allowed",
                title = "Forbidden Scan Path",
                detail = cause.message ?: "Requested path is outside allowed scan roots",
                apiJson = apiJson,
            )
        }
        exception<RuleValidationException> { call, cause ->
            call.respondProblem(
                status = HttpStatusCode.UnprocessableEntity,
                type = "https://auditor.lab.dev/problems/invalid-rules",
                title = "Invalid Architecture Rules",
                detail = cause.message ?: "Rule validation failed",
                apiJson = apiJson,
            )
        }
        exception<ScanNotFoundException> { call, cause ->
            call.respondProblem(
                status = HttpStatusCode.NotFound,
                type = "https://auditor.lab.dev/problems/scan-not-found",
                title = "Scan Not Found",
                detail = cause.message ?: "Requested scan ID does not exist",
                apiJson = apiJson,
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respondProblem(
                status = HttpStatusCode.BadRequest,
                type = "https://auditor.lab.dev/problems/bad-request",
                title = "Invalid Request",
                detail = cause.message ?: "Invalid request parameter",
                apiJson = apiJson,
            )
        }
        exception<Throwable> { call, _ ->
            call.respondProblem(
                status = HttpStatusCode.InternalServerError,
                type = "https://auditor.lab.dev/problems/internal-error",
                title = "Internal Server Error",
                detail = "An unexpected error occurred while processing the request.",
                apiJson = apiJson,
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondProblem(
                status = status,
                type = "https://auditor.lab.dev/problems/not-found",
                title = "Resource Not Found",
                detail = "Endpoint ${call.request.path()} was not found",
                apiJson = apiJson,
            )
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondProblem(
                status = status,
                type = "https://auditor.lab.dev/problems/method-not-allowed",
                title = "Method Not Allowed",
                detail = "HTTP method ${call.request.httpMethod.value} is not allowed for ${call.request.path()}",
                apiJson = apiJson,
            )
        }
    }

    // Correlation ID + latency/metrics interceptor
    intercept(ApplicationCallPipeline.Monitoring) {
        val rawCid = call.request.headers["X-Correlation-ID"]
            ?.replace(Regex("[^A-Za-z0-9._-]"), "")
            ?.take(64)
            ?.ifBlank { null }
        val correlationId = rawCid ?: UUID.randomUUID().toString()
        call.attributes.put(CorrelationIdKey, correlationId)
        call.attributes.put(RequestStartNanosKey, System.nanoTime())
        call.response.header("X-Correlation-ID", correlationId)
        try {
            proceed()
        } finally {
            val start = call.attributes.getOrNull(RequestStartNanosKey) ?: System.nanoTime()
            val durationMs = (System.nanoTime() - start) / 1_000_000L
            val statusCode = call.response.status()?.value ?: 200
            metrics.recordHttpRequest(call.request.httpMethod.value, normalizeMetricPath(call.request.path()), statusCode, durationMs)
        }
    }

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "ok"))
        }

        get("/ready") {
            if (repository.isHealthy()) {
                call.respond(HttpStatusCode.OK, HealthResponse(status = "ready"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(status = "not_ready"))
            }
        }

        get("/metrics") {
            call.respondText(
                text = metrics.renderPrometheus(),
                contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
                status = HttpStatusCode.OK,
            )
        }

        route("/api/v1") {
            install(
                createRouteScopedPlugin("ApiKeyAuth") {
                    onCall { call ->
                        verifyApiKey(call, config.apiKey)
                    }
                },
            )

            get("/scans") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                call.respond(HttpStatusCode.OK, auditUseCase.listScans(limit))
            }

            post("/scans") {
                val req = try {
                    call.receive<CreateScanRequest>()
                } catch (_: Exception) {
                    throw IllegalArgumentException("Malformed JSON body for scan request")
                }

                if (req.repositoryPath.isBlank()) {
                    throw IllegalArgumentException("repositoryPath must not be blank")
                }

                val validatedRepo = scanner.validateAllowedRoot(
                    requestedPath = Path.of(req.repositoryPath),
                    allowedRoots = config.allowedScanRoots,
                )

                val ruleSet = when {
                    !req.rulesYaml.isNullOrBlank() -> RuleLoader.parseYaml(req.rulesYaml)
                    !req.rulesJson.isNullOrBlank() -> RuleLoader.parseJson(req.rulesJson)
                    !req.rulesPath.isNullOrBlank() -> {
                        val rulePath = Path.of(req.rulesPath).toAbsolutePath().normalize()
                        val parentReal = rulePath.parent?.toRealPath()
                            ?: throw PathTraversalException("Invalid rulesPath")
                        val permitted = config.allowedScanRoots.any { allowed ->
                            val realAllowed = allowed.toAbsolutePath().normalize().toRealPath()
                            parentReal.startsWith(realAllowed)
                        }
                        if (!permitted) {
                            throw PathTraversalException("rulesPath is outside AUDITOR_ALLOWED_ROOTS")
                        }
                        RuleLoader.loadFromFile(rulePath)
                    }
                    else -> RuleLoader.defaultHexagonalRuleSet()
                }

                val correlationId = call.attributes[CorrelationIdKey]
                val report = auditUseCase.runAudit(
                    repositoryPath = validatedRepo,
                    ruleSet = ruleSet,
                    includeAiAdvice = req.includeAiAdvice,
                    correlationId = correlationId,
                    actor = "api-key-client",
                )
                metrics.recordScan(
                    violations = report.verifiedFindings.violationCount,
                    cycles = report.verifiedFindings.cycleCount,
                    cacheHits = report.telemetry.cacheHits,
                    cacheMisses = report.telemetry.cacheMisses,
                )
                call.respond(HttpStatusCode.Created, report)
            }

            get("/scans/diff") {
                val base = call.request.queryParameters["base"]?.trim().orEmpty()
                val head = call.request.queryParameters["head"]?.trim().orEmpty()
                if (base.isEmpty() || head.isEmpty()) {
                    throw IllegalArgumentException("Both 'base' and 'head' query parameters are required")
                }
                val diff = auditUseCase.diffScans(base, head)
                call.respond(HttpStatusCode.OK, diff)
            }

            get("/scans/{id}") {
                val id = call.parameters["id"].orEmpty()
                if (id.isBlank()) throw IllegalArgumentException("Scan id must not be blank")
                call.respond(HttpStatusCode.OK, auditUseCase.getScan(id))
            }

            get("/scans/{id}/export") {
                val id = call.parameters["id"].orEmpty()
                val formatParam = call.request.queryParameters["format"] ?: "json"
                val format = ExportFormat.parse(formatParam)
                val report = auditUseCase.getScan(id)
                val exported = ReportExporter.export(report, format)
                call.respondText(
                    text = exported,
                    contentType = ContentType.parse(ReportExporter.contentType(format)),
                    status = HttpStatusCode.OK,
                )
            }

            post("/scans/{id}/explain") {
                val id = call.parameters["id"].orEmpty()
                val correlationId = call.attributes[CorrelationIdKey]
                val updated = auditUseCase.explainExistingScan(
                    scanId = id,
                    correlationId = correlationId,
                    actor = "api-key-client",
                )
                call.respond(HttpStatusCode.OK, updated)
            }
        }
    }
}

private fun verifyApiKey(call: ApplicationCall, expectedApiKey: String) {
    if (expectedApiKey.isBlank()) {
        throw UnauthorizedException("Server API key is not configured")
    }
    val provided = call.request.headers["X-API-Key"].orEmpty()
    val valid = provided.isNotEmpty() && MessageDigest.isEqual(
        provided.toByteArray(Charsets.UTF_8),
        expectedApiKey.toByteArray(Charsets.UTF_8),
    )
    if (!valid) {
        throw UnauthorizedException("Missing or invalid X-API-Key header")
    }
}

private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    type: String,
    title: String,
    detail: String,
    apiJson: Json,
) {
    val correlationId = attributes.getOrNull(CorrelationIdKey) ?: "unknown"
    val problem = ProblemDetails(
        type = type,
        title = title,
        status = status.value,
        detail = detail,
        instance = request.path(),
        correlationId = correlationId,
    )
    val payload = apiJson.encodeToString(ProblemDetails.serializer(), problem)
    respondText(
        text = payload,
        contentType = ContentType.parse("application/problem+json"),
        status = status,
    )
}

private fun normalizeMetricPath(path: String): String = when {
    path.startsWith("/api/v1/scans/diff") -> "/api/v1/scans/diff"
    path.matches(Regex("/api/v1/scans/[^/]+/export")) -> "/api/v1/scans/{id}/export"
    path.matches(Regex("/api/v1/scans/[^/]+/explain")) -> "/api/v1/scans/{id}/explain"
    path.matches(Regex("/api/v1/scans/[^/]+")) -> "/api/v1/scans/{id}"
    else -> path
}
