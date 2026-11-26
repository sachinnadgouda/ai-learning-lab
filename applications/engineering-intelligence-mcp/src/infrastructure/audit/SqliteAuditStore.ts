import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import {
  AuditStorePort,
  ConfirmationStorePort,
  WriteReconciliationStorePort,
} from '../../application/ports.js';
import {
  AuditRecord,
  ConfirmationRecord,
  DeploymentMode,
  WriteReconciliationRecord,
} from '../../domain/entities.js';

interface Migration {
  version: number;
  name: string;
  sql: string;
}

const MIGRATIONS: Migration[] = [
  {
    version: 1,
    name: 'init_audit_logs_and_confirmations',
    sql: `
      CREATE TABLE IF NOT EXISTS audit_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        correlation_id TEXT NOT NULL,
        timestamp TEXT NOT NULL,
        actor TEXT NOT NULL,
        tool_name TEXT NOT NULL,
        permission_class TEXT NOT NULL,
        repository TEXT,
        outcome TEXT NOT NULL,
        error_code TEXT,
        duration_ms INTEGER NOT NULL,
        injection_warnings_count INTEGER NOT NULL DEFAULT 0,
        redacted_args_json TEXT NOT NULL,
        summary TEXT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_audit_logs_timestamp ON audit_logs(timestamp DESC);
      CREATE INDEX IF NOT EXISTS idx_audit_logs_tool_name ON audit_logs(tool_name);

      CREATE TABLE IF NOT EXISTS confirmations (
        nonce TEXT PRIMARY KEY,
        token_hash TEXT NOT NULL UNIQUE,
        tool_name TEXT NOT NULL,
        actor TEXT NOT NULL,
        repository TEXT NOT NULL,
        args_digest TEXT NOT NULL,
        action_preview TEXT NOT NULL,
        status TEXT NOT NULL,
        created_at TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        consumed_at TEXT
      );

      CREATE INDEX IF NOT EXISTS idx_confirmations_status_expires ON confirmations(status, expires_at);
    `,
  },
  {
    version: 2,
    name: 'confirmation_session_binding_and_write_reconciliations',
    sql: `
      ALTER TABLE confirmations ADD COLUMN session_id TEXT NOT NULL DEFAULT 'local-stdio-session';
      ALTER TABLE confirmations ADD COLUMN deployment_mode TEXT NOT NULL DEFAULT 'local-stdio';

      CREATE TABLE IF NOT EXISTS write_reconciliations (
        correlation_id TEXT PRIMARY KEY,
        tool_name TEXT NOT NULL,
        repository TEXT NOT NULL,
        target_number INTEGER,
        operation_marker TEXT NOT NULL,
        expected_labels_json TEXT NOT NULL,
        actor TEXT NOT NULL,
        status TEXT NOT NULL,
        created_at TEXT NOT NULL,
        resolved_at TEXT,
        resolution_detail TEXT
      );

      CREATE INDEX IF NOT EXISTS idx_write_reconciliations_status ON write_reconciliations(status, created_at DESC);
    `,
  },
];

export class SqliteAuditStore
  implements AuditStorePort, ConfirmationStorePort, WriteReconciliationStorePort
{
  private readonly db: DatabaseSync;

  constructor(dbPath: string) {
    if (dbPath !== ':memory:') {
      mkdirSync(dirname(dbPath), { recursive: true });
    }
    this.db = new DatabaseSync(dbPath);
    if (dbPath !== ':memory:') {
      this.db.exec('PRAGMA journal_mode = WAL;');
    }
    this.db.exec('PRAGMA busy_timeout = 5000;');
    this.db.exec('PRAGMA foreign_keys = ON;');
    this.applyMigrations();
  }

  public close(): void {
    this.db.close();
  }

  public append(record: AuditRecord): void {
    const stmt = this.db.prepare(`
      INSERT INTO audit_logs (
        correlation_id,
        timestamp,
        actor,
        tool_name,
        permission_class,
        repository,
        outcome,
        error_code,
        duration_ms,
        injection_warnings_count,
        redacted_args_json,
        summary
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);

    stmt.run(
      record.correlationId,
      record.timestamp,
      record.actor,
      record.toolName,
      record.permissionClass,
      record.repository,
      record.outcome,
      record.errorCode,
      Math.round(record.durationMs),
      record.injectionWarningsCount,
      record.redactedArgsJson,
      record.summary,
    );
  }

  public listRecent(options?: { limit?: number; offset?: number; toolName?: string }): AuditRecord[] {
    const limit = Math.min(200, Math.max(1, options?.limit ?? 25));
    const offset = Math.max(0, options?.offset ?? 0);

    const rows = options?.toolName
      ? (this.db
          .prepare(
            `SELECT * FROM audit_logs WHERE tool_name = ? ORDER BY id DESC LIMIT ? OFFSET ?`,
          )
          .all(options.toolName, limit, offset) as Array<Record<string, unknown>>)
      : (this.db
          .prepare(`SELECT * FROM audit_logs ORDER BY id DESC LIMIT ? OFFSET ?`)
          .all(limit, offset) as Array<Record<string, unknown>>);

    return rows.map((row) => ({
      id: Number(row.id),
      correlationId: String(row.correlation_id),
      timestamp: String(row.timestamp),
      actor: String(row.actor),
      toolName: String(row.tool_name),
      permissionClass: String(row.permission_class) as AuditRecord['permissionClass'],
      repository: row.repository === null ? null : String(row.repository),
      outcome: String(row.outcome) as AuditRecord['outcome'],
      errorCode: row.error_code === null ? null : String(row.error_code),
      durationMs: Number(row.duration_ms),
      injectionWarningsCount: Number(row.injection_warnings_count),
      redactedArgsJson: String(row.redacted_args_json),
      summary: String(row.summary),
    }));
  }

  public count(): number {
    const row = this.db.prepare('SELECT COUNT(*) as total FROM audit_logs').get() as
      | { total: number }
      | undefined;
    return Number(row?.total ?? 0);
  }

  public saveConfirmation(record: ConfirmationRecord): void {
    const stmt = this.db.prepare(`
      INSERT INTO confirmations (
        nonce,
        token_hash,
        tool_name,
        actor,
        session_id,
        deployment_mode,
        repository,
        args_digest,
        action_preview,
        status,
        created_at,
        expires_at,
        consumed_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);

    stmt.run(
      record.nonce,
      record.tokenHash,
      record.toolName,
      record.actor,
      record.sessionId,
      record.deploymentMode,
      record.repository,
      record.argsDigest,
      record.actionPreview,
      record.status,
      record.createdAt,
      record.expiresAt,
      record.consumedAt,
    );
  }

  public findConfirmationByNonce(nonce: string): ConfirmationRecord | null {
    const row = this.db
      .prepare('SELECT * FROM confirmations WHERE nonce = ?')
      .get(nonce) as Record<string, unknown> | undefined;

    if (!row) {
      return null;
    }

    return mapConfirmationRow(row);
  }

  public consumeConfirmation(nonce: string, consumedAt: string): boolean {
    const result = this.db
      .prepare(
        `UPDATE confirmations
         SET status = 'CONSUMED', consumed_at = ?
         WHERE nonce = ? AND status = 'PENDING'`,
      )
      .run(consumedAt, nonce);
    return Number(result.changes) === 1;
  }

  public expireOldConfirmations(nowIso: string): number {
    const result = this.db
      .prepare(
        `UPDATE confirmations
         SET status = 'EXPIRED'
         WHERE status = 'PENDING' AND expires_at <= ?`,
      )
      .run(nowIso);
    return Number(result.changes);
  }

  public listRecentConfirmations(limit = 20): ConfirmationRecord[] {
    const rows = this.db
      .prepare('SELECT * FROM confirmations ORDER BY created_at DESC LIMIT ?')
      .all(Math.min(100, Math.max(1, limit))) as Array<Record<string, unknown>>;
    return rows.map(mapConfirmationRow);
  }

  public saveWriteReconciliation(record: WriteReconciliationRecord): void {
    const stmt = this.db.prepare(`
      INSERT OR REPLACE INTO write_reconciliations (
        correlation_id,
        tool_name,
        repository,
        target_number,
        operation_marker,
        expected_labels_json,
        actor,
        status,
        created_at,
        resolved_at,
        resolution_detail
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);

    stmt.run(
      record.correlationId,
      record.toolName,
      record.repository,
      record.targetNumber,
      record.operationMarker,
      JSON.stringify(record.expectedLabels),
      record.actor,
      record.status,
      record.createdAt,
      record.resolvedAt,
      record.resolutionDetail,
    );
  }

  public findWriteReconciliation(correlationId: string): WriteReconciliationRecord | null {
    const row = this.db
      .prepare('SELECT * FROM write_reconciliations WHERE correlation_id = ?')
      .get(correlationId) as Record<string, unknown> | undefined;
    if (!row) {
      return null;
    }
    return mapWriteReconciliationRow(row);
  }

  public resolveWriteReconciliation(
    correlationId: string,
    status: 'CONFIRMED_APPLIED' | 'NOT_FOUND_UPSTREAM',
    resolvedAt: string,
    resolutionDetail: string,
  ): boolean {
    const result = this.db
      .prepare(
        `UPDATE write_reconciliations
         SET status = ?, resolved_at = ?, resolution_detail = ?
         WHERE correlation_id = ?`,
      )
      .run(status, resolvedAt, resolutionDetail, correlationId);
    return Number(result.changes) === 1;
  }

  public listPendingWriteReconciliations(limit = 25): WriteReconciliationRecord[] {
    const rows = this.db
      .prepare(
        `SELECT * FROM write_reconciliations
         WHERE status = 'AMBIGUOUS'
         ORDER BY created_at DESC
         LIMIT ?`,
      )
      .all(Math.min(100, Math.max(1, limit))) as Array<Record<string, unknown>>;
    return rows.map(mapWriteReconciliationRow);
  }

  private applyMigrations(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version INTEGER PRIMARY KEY,
        name TEXT NOT NULL,
        applied_at TEXT NOT NULL
      );
    `);

    const appliedRows = this.db
      .prepare('SELECT version FROM schema_migrations')
      .all() as Array<{ version: number }>;
    const applied = new Set(appliedRows.map((r) => Number(r.version)));

    for (const migration of MIGRATIONS) {
      if (applied.has(migration.version)) {
        continue;
      }
      this.db.exec('BEGIN');
      try {
        this.db.exec(migration.sql);
        this.db
          .prepare('INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)')
          .run(migration.version, migration.name, new Date().toISOString());
        this.db.exec('COMMIT');
      } catch (err) {
        this.db.exec('ROLLBACK');
        throw err;
      }
    }
  }
}

function mapConfirmationRow(row: Record<string, unknown>): ConfirmationRecord {
  return {
    nonce: String(row.nonce),
    tokenHash: String(row.token_hash),
    toolName: String(row.tool_name),
    actor: String(row.actor),
    sessionId: String(row.session_id ?? 'local-stdio-session'),
    deploymentMode: String(row.deployment_mode ?? 'local-stdio') as DeploymentMode,
    repository: String(row.repository),
    argsDigest: String(row.args_digest),
    actionPreview: String(row.action_preview),
    status: String(row.status) as ConfirmationRecord['status'],
    createdAt: String(row.created_at),
    expiresAt: String(row.expires_at),
    consumedAt: row.consumed_at === null ? null : String(row.consumed_at),
  };
}

function mapWriteReconciliationRow(row: Record<string, unknown>): WriteReconciliationRecord {
  let expectedLabels: string[] = [];
  try {
    const parsed = JSON.parse(String(row.expected_labels_json ?? '[]'));
    if (Array.isArray(parsed)) {
      expectedLabels = parsed.map((item) => String(item));
    }
  } catch {
    expectedLabels = [];
  }

  return {
    correlationId: String(row.correlation_id),
    toolName: String(row.tool_name) as WriteReconciliationRecord['toolName'],
    repository: String(row.repository),
    targetNumber: row.target_number === null ? null : Number(row.target_number),
    operationMarker: String(row.operation_marker),
    expectedLabels,
    actor: String(row.actor),
    status: String(row.status) as WriteReconciliationRecord['status'],
    createdAt: String(row.created_at),
    resolvedAt: row.resolved_at === null ? null : String(row.resolved_at),
    resolutionDetail: row.resolution_detail === null ? null : String(row.resolution_detail),
  };
}
