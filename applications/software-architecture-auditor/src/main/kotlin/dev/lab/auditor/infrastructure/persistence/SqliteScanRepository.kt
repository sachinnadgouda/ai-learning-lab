package dev.lab.auditor.infrastructure.persistence

import dev.lab.auditor.application.AuditEventPort
import dev.lab.auditor.application.CachedFileParse
import dev.lab.auditor.application.FileCachePort
import dev.lab.auditor.application.ScanRepositoryPort
import dev.lab.auditor.application.ScanSummary
import dev.lab.auditor.domain.AiSuggestionsEnvelope
import dev.lab.auditor.domain.AuditReport
import dev.lab.auditor.infrastructure.SecretRedactor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

class SqliteScanRepository(
    dbPath: String,
) : ScanRepositoryPort, FileCachePort, AuditEventPort {

    private val jdbcUrl: String
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    init {
        if (dbPath != ":memory:") {
            val path = Path.of(dbPath).toAbsolutePath().normalize()
            path.parent?.let { Files.createDirectories(it) }
            jdbcUrl = "jdbc:sqlite:$path"
        } else {
            jdbcUrl = "jdbc:sqlite::memory:"
        }

        // Run Flyway schema migrations
        Flyway.configure()
            .dataSource(jdbcUrl, null, null)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        // Enable WAL mode and busy timeout for safe concurrent reads/writes
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL;")
                stmt.execute("PRAGMA busy_timeout=5000;")
                stmt.execute("PRAGMA foreign_keys=ON;")
            }
        }
    }

    private inline fun <T> withConnection(block: (Connection) -> T): T {
        DriverManager.getConnection(jdbcUrl).use { conn ->
            return block(conn)
        }
    }

    override fun save(report: AuditReport) {
        val payload = json.encodeToString(AuditReport.serializer(), report)
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO scans (
                    scan_id, repository_path, ruleset_name, created_at,
                    deterministic_digest, violation_count, cycle_count, files_scanned, report_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, report.scanId)
                ps.setString(2, report.repositoryPath)
                ps.setString(3, report.rulesetName)
                ps.setString(4, report.createdAt)
                ps.setString(5, report.verifiedFindings.deterministicDigest)
                ps.setInt(6, report.verifiedFindings.violationCount)
                ps.setInt(7, report.verifiedFindings.cycleCount)
                ps.setInt(8, report.telemetry.filesScanned)
                ps.setString(9, payload)
                ps.executeUpdate()
            }
        }
    }

    override fun findById(scanId: String): AuditReport? = withConnection { conn ->
        conn.prepareStatement("SELECT report_json FROM scans WHERE scan_id = ?").use { ps ->
            ps.setString(1, scanId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    json.decodeFromString(AuditReport.serializer(), rs.getString("report_json"))
                } else {
                    null
                }
            }
        }
    }

    override fun listRecent(limit: Int): List<ScanSummary> = withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT scan_id, repository_path, ruleset_name, created_at,
                   deterministic_digest, violation_count, cycle_count, files_scanned
            FROM scans
            ORDER BY created_at DESC, scan_id DESC
            LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<ScanSummary>()
                while (rs.next()) {
                    list.add(
                        ScanSummary(
                            scanId = rs.getString("scan_id"),
                            repositoryPath = rs.getString("repository_path"),
                            rulesetName = rs.getString("ruleset_name"),
                            createdAt = rs.getString("created_at"),
                            deterministicDigest = rs.getString("deterministic_digest"),
                            violationCount = rs.getInt("violation_count"),
                            cycleCount = rs.getInt("cycle_count"),
                            filesScanned = rs.getInt("files_scanned"),
                        ),
                    )
                }
                list
            }
        }
    }

    override fun updateAiSuggestions(scanId: String, envelope: AiSuggestionsEnvelope): AuditReport? {
        val existing = findById(scanId) ?: return null
        val updated = existing.attachAdvisorySuggestions(envelope)
        save(updated)
        return updated
    }

    override fun isHealthy(): Boolean = runCatching {
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT 1").use { rs -> rs.next() && rs.getInt(1) == 1 }
            }
        }
    }.getOrDefault(false)

    override fun get(relativePath: String, sha256: String): CachedFileParse? = withConnection { conn ->
        conn.prepareStatement(
            "SELECT payload_json FROM file_parse_cache WHERE relative_path = ? AND sha256 = ?",
        ).use { ps ->
            ps.setString(1, relativePath)
            ps.setString(2, sha256)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    runCatching {
                        json.decodeFromString(CachedFileParse.serializer(), rs.getString("payload_json"))
                    }.getOrNull()
                } else {
                    null
                }
            }
        }
    }

    override fun put(entry: CachedFileParse) {
        val payload = json.encodeToString(CachedFileParse.serializer(), entry)
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO file_parse_cache (
                    relative_path, sha256, language, package_name, line_count, payload_json, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, entry.relativePath)
                ps.setString(2, entry.sha256)
                ps.setString(3, entry.language.name)
                ps.setString(4, entry.packageName)
                ps.setInt(5, entry.lineCount)
                ps.setString(6, payload)
                ps.setString(7, Instant.now().toString())
                ps.executeUpdate()
            }
        }
    }

    override fun record(
        eventType: String,
        correlationId: String?,
        actor: String,
        detail: Map<String, String>,
    ) {
        val redacted = detail.mapValues { (_, v) -> SecretRedactor.redact(v) }
        val detailJson = json.encodeToString(redacted)
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO audit_events (event_type, correlation_id, actor, detail_json, created_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, eventType)
                ps.setString(2, correlationId)
                ps.setString(3, SecretRedactor.redact(actor))
                ps.setString(4, detailJson)
                ps.setString(5, Instant.now().toString())
                ps.executeUpdate()
            }
        }
    }

    fun countAuditEvents(): Long = withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM audit_events").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }
}
