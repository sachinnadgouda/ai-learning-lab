CREATE TABLE IF NOT EXISTS scans (
    scan_id TEXT PRIMARY KEY,
    repository_path TEXT NOT NULL,
    ruleset_name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    deterministic_digest TEXT NOT NULL,
    violation_count INTEGER NOT NULL,
    cycle_count INTEGER NOT NULL,
    files_scanned INTEGER NOT NULL,
    report_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_scans_created_at ON scans (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_scans_digest ON scans (deterministic_digest);

CREATE TABLE IF NOT EXISTS file_parse_cache (
    relative_path TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    language TEXT NOT NULL,
    package_name TEXT NOT NULL,
    line_count INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (relative_path, sha256)
);

CREATE TABLE IF NOT EXISTS audit_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT NOT NULL,
    correlation_id TEXT,
    actor TEXT NOT NULL,
    detail_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_events_created_at ON audit_events (created_at DESC);
