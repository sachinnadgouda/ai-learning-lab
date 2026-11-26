-- 001_init.sql: Initial schema for Supply Chain Risk Intelligence

CREATE TABLE IF NOT EXISTS suppliers (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    region TEXT NOT NULL,
    category TEXT NOT NULL DEFAULT '',
    tier INTEGER NOT NULL DEFAULT 2,
    single_source INTEGER NOT NULL DEFAULT 0,
    historical_delay_ratio REAL NOT NULL DEFAULT 0,
    active_disruption_severity REAL NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'OPERATIONAL',
    lead_time_days REAL NOT NULL DEFAULT 14,
    unit_cost_usd REAL NOT NULL DEFAULT 10,
    backup_for_id TEXT NOT NULL DEFAULT '',
    last_occurred_at TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS routes (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    origin TEXT NOT NULL,
    destination TEXT NOT NULL,
    mode TEXT NOT NULL DEFAULT 'OCEAN',
    base_transit_days REAL NOT NULL DEFAULT 14,
    transit_delay_variance_days REAL NOT NULL DEFAULT 0,
    congestion_severity REAL NOT NULL DEFAULT 0,
    weather_incident_count INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'CLEAR',
    cost_multiplier REAL NOT NULL DEFAULT 1.0,
    backup_for_id TEXT NOT NULL DEFAULT '',
    last_occurred_at TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS shipments (
    id TEXT PRIMARY KEY,
    supplier_id TEXT NOT NULL,
    route_id TEXT NOT NULL,
    sku TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'IN_TRANSIT',
    scheduled_eta TEXT NOT NULL,
    current_eta TEXT NOT NULL,
    delay_hours REAL NOT NULL DEFAULT 0,
    notes TEXT NOT NULL DEFAULT '',
    last_occurred_at TEXT,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_shipments_supplier ON shipments(supplier_id);
CREATE INDEX IF NOT EXISTS idx_shipments_route ON shipments(route_id);
CREATE INDEX IF NOT EXISTS idx_shipments_sku ON shipments(sku);

CREATE TABLE IF NOT EXISTS inventory_items (
    sku TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    warehouse TEXT NOT NULL,
    primary_supplier_id TEXT NOT NULL,
    on_hand_units INTEGER NOT NULL DEFAULT 0,
    daily_burn_rate REAL NOT NULL DEFAULT 0,
    safety_stock_days REAL NOT NULL DEFAULT 7,
    reorder_point_units INTEGER NOT NULL DEFAULT 0,
    lead_time_days REAL NOT NULL DEFAULT 14,
    last_occurred_at TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS processed_events (
    event_id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    received_at TEXT NOT NULL,
    status TEXT NOT NULL,
    out_of_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS supply_events (
    event_id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    received_at TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    version INTEGER NOT NULL DEFAULT 1,
    correlation_id TEXT NOT NULL DEFAULT '',
    processing_status TEXT NOT NULL DEFAULT 'PROCESSED',
    out_of_order INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_supply_events_occurred ON supply_events(occurred_at ASC);
CREATE INDEX IF NOT EXISTS idx_supply_events_entity ON supply_events(entity_id);

CREATE TABLE IF NOT EXISTS incidents (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    status TEXT NOT NULL,
    severity TEXT NOT NULL,
    root_supplier_id TEXT NOT NULL DEFAULT '',
    risk_score REAL NOT NULL DEFAULT 0,
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS change_proposals (
    id TEXT PRIMARY KEY,
    incident_id TEXT NOT NULL DEFAULT '',
    proposal_type TEXT NOT NULL,
    target_entity_id TEXT NOT NULL,
    parameters_json TEXT NOT NULL DEFAULT '{}',
    summary TEXT NOT NULL,
    rationale TEXT NOT NULL DEFAULT '',
    proposed_by TEXT NOT NULL,
    proposed_by_type TEXT NOT NULL,
    status TEXT NOT NULL,
    approved_by TEXT NOT NULL DEFAULT '',
    approved_by_type TEXT NOT NULL DEFAULT '',
    approval_comment TEXT NOT NULL DEFAULT '',
    rejected_by TEXT NOT NULL DEFAULT '',
    rejected_reason TEXT NOT NULL DEFAULT '',
    executed_by TEXT NOT NULL DEFAULT '',
    executed_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_proposals_status ON change_proposals(status);

CREATE TABLE IF NOT EXISTS dead_letter_events (
    id TEXT PRIMARY KEY,
    event_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    raw_payload TEXT NOT NULL,
    error_reason TEXT NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    replayed INTEGER NOT NULL DEFAULT 0,
    replayed_at TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_dlq_created ON dead_letter_events(created_at DESC);

CREATE TABLE IF NOT EXISTS alert_notifications (
    id TEXT PRIMARY KEY,
    incident_id TEXT NOT NULL,
    severity TEXT NOT NULL,
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    channel TEXT NOT NULL,
    delivered INTEGER NOT NULL DEFAULT 1,
    error TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id TEXT PRIMARY KEY,
    correlation_id TEXT NOT NULL DEFAULT '',
    actor TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    action TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    outcome TEXT NOT NULL,
    details_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs(created_at DESC);
