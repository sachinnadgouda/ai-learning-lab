package dev.lab.auditor.infrastructure

import dev.lab.auditor.application.ScannerLimits
import java.nio.file.Path

enum class AiProviderType {
    NONE,
    OLLAMA,
    OPENAI_COMPATIBLE;

    companion object {
        fun parse(raw: String): AiProviderType = when (raw.trim().lowercase()) {
            "", "none", "disabled" -> NONE
            "ollama" -> OLLAMA
            "openai", "openai-compatible", "openai_compatible" -> OPENAI_COMPATIBLE
            else -> throw IllegalArgumentException(
                "Invalid AI_PROVIDER '$raw'. Allowed values: none, ollama, openai-compatible",
            )
        }
    }
}

data class AuditorConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8083,
    val dbPath: String = "data/auditor.db",
    val apiKey: String = "",
    val allowedScanRoots: List<Path> = listOf(Path.of("examples").toAbsolutePath().normalize()),
    val maxFiles: Int = 5_000,
    val maxFileBytes: Long = 1_048_576L,
    val maxDepth: Int = 20,
    val aiProvider: AiProviderType = AiProviderType.NONE,
    val aiBaseUrl: String = "http://127.0.0.1:11434",
    val aiModel: String = "llama3.2:3b",
    val aiApiKey: String = "",
    val aiConnectTimeoutMs: Long = 5_000L,
    val aiRequestTimeoutMs: Long = 20_000L,
    val aiMaxRetries: Int = 2,
    val corsAllowedOrigins: List<String> = emptyList(),
) {
    fun validate(requireServerApiKey: Boolean = false) {
        val errors = mutableListOf<String>()
        if (port !in 1..65535) errors.add("AUDITOR_PORT must be in 1..65535 (got $port)")
        if (dbPath.isBlank()) errors.add("AUDITOR_DB_PATH must not be blank")
        if (maxFiles <= 0) errors.add("AUDITOR_MAX_FILES must be > 0")
        if (maxFileBytes <= 0) errors.add("AUDITOR_MAX_FILE_BYTES must be > 0")
        if (maxDepth <= 0) errors.add("AUDITOR_MAX_DEPTH must be > 0")
        if (aiConnectTimeoutMs <= 0) errors.add("AI_CONNECT_TIMEOUT_MS must be > 0")
        if (aiRequestTimeoutMs <= 0) errors.add("AI_REQUEST_TIMEOUT_MS must be > 0")
        if (aiMaxRetries < 0 || aiMaxRetries > 5) errors.add("AI_MAX_RETRIES must be in 0..5")
        if (aiProvider != AiProviderType.NONE) {
            if (!aiBaseUrl.startsWith("http://") && !aiBaseUrl.startsWith("https://")) {
                errors.add("AI_BASE_URL must start with http:// or https:// when AI_PROVIDER != none")
            }
            if (aiModel.isBlank()) {
                errors.add("AI_MODEL must not be blank when AI_PROVIDER != none")
            }
        }
        if (requireServerApiKey && apiKey.length < 16) {
            errors.add("AUDITOR_API_KEY must be at least 16 characters when running HTTP server")
        }
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException("Invalid configuration: ${errors.joinToString("; ")}")
        }
    }

    fun toScannerLimits(): ScannerLimits = ScannerLimits(
        maxFiles = maxFiles,
        maxFileBytes = maxFileBytes,
        maxDepth = maxDepth,
    )

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AuditorConfig {
            val allowedRootsRaw = env["AUDITOR_ALLOWED_ROOTS"]?.trim().orEmpty()
            val allowedRoots = if (allowedRootsRaw.isBlank()) {
                listOf(Path.of("examples").toAbsolutePath().normalize())
            } else {
                allowedRootsRaw.split(',', ';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { Path.of(it).toAbsolutePath().normalize() }
            }

            val corsRaw = env["AUDITOR_CORS_ORIGINS"]?.trim().orEmpty()
            val cors = if (corsRaw.isBlank()) emptyList() else corsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

            return AuditorConfig(
                host = env["AUDITOR_HOST"]?.ifBlank { null } ?: "127.0.0.1",
                port = env["AUDITOR_PORT"]?.toIntOrNull() ?: 8083,
                dbPath = env["AUDITOR_DB_PATH"]?.ifBlank { null } ?: "data/auditor.db",
                apiKey = env["AUDITOR_API_KEY"].orEmpty(),
                allowedScanRoots = allowedRoots,
                maxFiles = env["AUDITOR_MAX_FILES"]?.toIntOrNull() ?: 5_000,
                maxFileBytes = env["AUDITOR_MAX_FILE_BYTES"]?.toLongOrNull() ?: 1_048_576L,
                maxDepth = env["AUDITOR_MAX_DEPTH"]?.toIntOrNull() ?: 20,
                aiProvider = AiProviderType.parse(env["AI_PROVIDER"].orEmpty()),
                aiBaseUrl = env["AI_BASE_URL"]?.ifBlank { null } ?: "http://127.0.0.1:11434",
                aiModel = env["AI_MODEL"]?.ifBlank { null } ?: "llama3.2:3b",
                aiApiKey = env["AI_API_KEY"].orEmpty(),
                aiConnectTimeoutMs = env["AI_CONNECT_TIMEOUT_MS"]?.toLongOrNull() ?: 5_000L,
                aiRequestTimeoutMs = env["AI_REQUEST_TIMEOUT_MS"]?.toLongOrNull() ?: 20_000L,
                aiMaxRetries = env["AI_MAX_RETRIES"]?.toIntOrNull() ?: 2,
                corsAllowedOrigins = cors,
            )
        }
    }
}
