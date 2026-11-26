package sqlite

import (
	"context"
	"database/sql"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// PersistenceFaultHook is an optional failure-injection hook invoked at named persistence boundaries
// (begin_tx, check_claim, read_entity, mutate_entity, append_event, complete_claim, commit_tx,
// dlq_claim, dlq_insert, dlq_commit) to deterministically test transactional atomicity and recovery.
type PersistenceFaultHook func(stage string, eventID string) error

// Store implements application.Repository backed by pure-Go SQLite with WAL mode and embedded migrations.
type Store struct {
	db *sql.DB

	faultMu   sync.RWMutex
	faultHook PersistenceFaultHook
}

// SetFaultHook registers an optional persistence failure-injection hook (primarily for tests).
func (s *Store) SetFaultHook(fn PersistenceFaultHook) {
	s.faultMu.Lock()
	defer s.faultMu.Unlock()
	s.faultHook = fn
}

func (s *Store) injectFault(stage, eventID string) error {
	s.faultMu.RLock()
	fn := s.faultHook
	s.faultMu.RUnlock()
	if fn != nil {
		return fn(stage, eventID)
	}
	return nil
}

// Compile-time check that Store implements application.Repository.
var _ application.Repository = (*Store)(nil)

func Open(dsn string) (*Store, error) {
	if dsn != ":memory:" && !strings.HasPrefix(dsn, "file:") {
		dir := filepath.Dir(dsn)
		if dir != "" && dir != "." {
			if err := os.MkdirAll(dir, 0o750); err != nil {
				return nil, fmt.Errorf("create sqlite data dir: %w", err)
			}
		}
	}

	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	// Single connection pool avoids SQLITE_BUSY contention across concurrent worker goroutines
	// and ensures :memory: databases remain shared across all goroutines.
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)

	pragmas := []string{
		"PRAGMA busy_timeout = 5000;",
		"PRAGMA foreign_keys = ON;",
	}
	if dsn != ":memory:" {
		pragmas = append([]string{"PRAGMA journal_mode = WAL;"}, pragmas...)
	}
	for _, p := range pragmas {
		if _, err := db.Exec(p); err != nil {
			_ = db.Close()
			return nil, fmt.Errorf("apply sqlite pragma %q: %w", p, err)
		}
	}

	s := &Store{db: db}
	if err := s.migrate(context.Background()); err != nil {
		_ = db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error {
	return s.db.Close()
}

func (s *Store) Ping(ctx context.Context) error {
	return s.db.PingContext(ctx)
}

func (s *Store) migrate(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			version TEXT PRIMARY KEY,
			applied_at TEXT NOT NULL
		);
	`); err != nil {
		return fmt.Errorf("create schema_migrations table: %w", err)
	}

	entries, err := fs.ReadDir(migrationsFS, "migrations")
	if err != nil {
		return fmt.Errorf("read embedded migrations: %w", err)
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".sql") {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)

	for _, name := range names {
		var exists int
		err := s.db.QueryRowContext(ctx, `SELECT COUNT(1) FROM schema_migrations WHERE version = ?`, name).Scan(&exists)
		if err != nil {
			return fmt.Errorf("check migration %s: %w", name, err)
		}
		if exists > 0 {
			continue
		}

		sqlBytes, err := migrationsFS.ReadFile("migrations/" + name)
		if err != nil {
			return fmt.Errorf("read migration %s: %w", name, err)
		}

		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			return fmt.Errorf("begin migration tx %s: %w", name, err)
		}
		if _, err := tx.ExecContext(ctx, string(sqlBytes)); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("execute migration %s: %w", name, err)
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)`, name, time.Now().UTC().Format(time.RFC3339Nano)); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("record migration %s: %w", name, err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit migration %s: %w", name, err)
		}
	}
	return nil
}

func formatTime(t time.Time) string {
	if t.IsZero() {
		return time.Now().UTC().Format(time.RFC3339Nano)
	}
	return t.UTC().Format(time.RFC3339Nano)
}

func formatTimePtr(t *time.Time) sql.NullString {
	if t == nil || t.IsZero() {
		return sql.NullString{}
	}
	return sql.NullString{String: t.UTC().Format(time.RFC3339Nano), Valid: true}
}

func parseTime(s string) time.Time {
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		t, _ = time.Parse(time.RFC3339, s)
	}
	return t.UTC()
}

func parseTimePtr(ns sql.NullString) *time.Time {
	if !ns.Valid || ns.String == "" {
		return nil
	}
	t := parseTime(ns.String)
	return &t
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// --- Suppliers ---

func (s *Store) UpsertSupplier(ctx context.Context, sup domain.Supplier) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO suppliers (
			id, name, region, category, tier, single_source,
			historical_delay_ratio, active_disruption_severity, status,
			lead_time_days, unit_cost_usd, backup_for_id, last_occurred_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET
			name=excluded.name,
			region=excluded.region,
			category=excluded.category,
			tier=excluded.tier,
			single_source=excluded.single_source,
			historical_delay_ratio=excluded.historical_delay_ratio,
			active_disruption_severity=excluded.active_disruption_severity,
			status=excluded.status,
			lead_time_days=excluded.lead_time_days,
			unit_cost_usd=excluded.unit_cost_usd,
			backup_for_id=excluded.backup_for_id,
			last_occurred_at=excluded.last_occurred_at,
			updated_at=excluded.updated_at
	`,
		sup.ID, sup.Name, sup.Region, sup.Category, sup.Tier, boolToInt(sup.SingleSource),
		sup.HistoricalDelayRatio, sup.ActiveDisruptionSeverity, string(sup.Status),
		sup.LeadTimeDays, sup.UnitCostUSD, sup.BackupForID,
		formatTimePtr(sup.LastOccurredAt), formatTime(sup.UpdatedAt),
	)
	return err
}

func (s *Store) GetSupplier(ctx context.Context, id string) (*domain.Supplier, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, name, region, category, tier, single_source,
		       historical_delay_ratio, active_disruption_severity, status,
		       lead_time_days, unit_cost_usd, backup_for_id, last_occurred_at, updated_at
		FROM suppliers WHERE id = ?
	`, id)
	var sup domain.Supplier
	var single int
	var status, updated string
	var lastOcc sql.NullString
	if err := row.Scan(
		&sup.ID, &sup.Name, &sup.Region, &sup.Category, &sup.Tier, &single,
		&sup.HistoricalDelayRatio, &sup.ActiveDisruptionSeverity, &status,
		&sup.LeadTimeDays, &sup.UnitCostUSD, &sup.BackupForID, &lastOcc, &updated,
	); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, fmt.Errorf("%w: supplier %s", domain.ErrNotFound, id)
		}
		return nil, err
	}
	sup.SingleSource = single != 0
	sup.Status = domain.SupplierStatus(status)
	sup.LastOccurredAt = parseTimePtr(lastOcc)
	sup.UpdatedAt = parseTime(updated)
	return &sup, nil
}

func (s *Store) ListSuppliers(ctx context.Context) ([]domain.Supplier, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, name, region, category, tier, single_source,
		       historical_delay_ratio, active_disruption_severity, status,
		       lead_time_days, unit_cost_usd, backup_for_id, last_occurred_at, updated_at
		FROM suppliers ORDER BY id ASC
	`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]domain.Supplier, 0)
	for rows.Next() {
		var sup domain.Supplier
		var single int
		var status, updated string
		var lastOcc sql.NullString
		if err := rows.Scan(
			&sup.ID, &sup.Name, &sup.Region, &sup.Category, &sup.Tier, &single,
			&sup.HistoricalDelayRatio, &sup.ActiveDisruptionSeverity, &status,
			&sup.LeadTimeDays, &sup.UnitCostUSD, &sup.BackupForID, &lastOcc, &updated,
		); err != nil {
			return nil, err
		}
		sup.SingleSource = single != 0
		sup.Status = domain.SupplierStatus(status)
		sup.LastOccurredAt = parseTimePtr(lastOcc)
		sup.UpdatedAt = parseTime(updated)
		out = append(out, sup)
	}
	return out, rows.Err()
}

// --- Routes ---

func (s *Store) UpsertRoute(ctx context.Context, r domain.Route) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO routes (
			id, name, origin, destination, mode, base_transit_days,
			transit_delay_variance_days, congestion_severity, weather_incident_count,
			status, cost_multiplier, backup_for_id, last_occurred_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET
			name=excluded.name,
			origin=excluded.origin,
			destination=excluded.destination,
			mode=excluded.mode,
			base_transit_days=excluded.base_transit_days,
			transit_delay_variance_days=excluded.transit_delay_variance_days,
			congestion_severity=excluded.congestion_severity,
			weather_incident_count=excluded.weather_incident_count,
			status=excluded.status,
			cost_multiplier=excluded.cost_multiplier,
			backup_for_id=excluded.backup_for_id,
			last_occurred_at=excluded.last_occurred_at,
			updated_at=excluded.updated_at
	`,
		r.ID, r.Name, r.Origin, r.Destination, r.Mode, r.BaseTransitDays,
		r.TransitDelayVarianceDays, r.CongestionSeverity, r.WeatherIncidentCount,
		string(r.Status), r.CostMultiplier, r.BackupForID,
		formatTimePtr(r.LastOccurredAt), formatTime(r.UpdatedAt),
	)
	return err
}

func (s *Store) GetRoute(ctx context.Context, id string) (*domain.Route, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, name, origin, destination, mode, base_transit_days,
		       transit_delay_variance_days, congestion_severity, weather_incident_count,
		       status, cost_multiplier, backup_for_id, last_occurred_at, updated_at
		FROM routes WHERE id = ?
	`, id)
	var r domain.Route
	var status, updated string
	var lastOcc sql.NullString
	if err := row.Scan(
		&r.ID, &r.Name, &r.Origin, &r.Destination, &r.Mode, &r.BaseTransitDays,
		&r.TransitDelayVarianceDays, &r.CongestionSeverity, &r.WeatherIncidentCount,
		&status, &r.CostMultiplier, &r.BackupForID, &lastOcc, &updated,
	); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, fmt.Errorf("%w: route %s", domain.ErrNotFound, id)
		}
		return nil, err
	}
	r.Status = domain.RouteStatus(status)
	r.LastOccurredAt = parseTimePtr(lastOcc)
	r.UpdatedAt = parseTime(updated)
	return &r, nil
}

func (s *Store) ListRoutes(ctx context.Context) ([]domain.Route, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, name, origin, destination, mode, base_transit_days,
		       transit_delay_variance_days, congestion_severity, weather_incident_count,
		       status, cost_multiplier, backup_for_id, last_occurred_at, updated_at
		FROM routes ORDER BY id ASC
	`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]domain.Route, 0)
	for rows.Next() {
		var r domain.Route
		var status, updated string
		var lastOcc sql.NullString
		if err := rows.Scan(
			&r.ID, &r.Name, &r.Origin, &r.Destination, &r.Mode, &r.BaseTransitDays,
			&r.TransitDelayVarianceDays, &r.CongestionSeverity, &r.WeatherIncidentCount,
			&status, &r.CostMultiplier, &r.BackupForID, &lastOcc, &updated,
		); err != nil {
			return nil, err
		}
		r.Status = domain.RouteStatus(status)
		r.LastOccurredAt = parseTimePtr(lastOcc)
		r.UpdatedAt = parseTime(updated)
		out = append(out, r)
	}
	return out, rows.Err()
}

// --- Shipments ---

func (s *Store) UpsertShipment(ctx context.Context, sh domain.Shipment) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO shipments (
			id, supplier_id, route_id, sku, quantity, status,
			scheduled_eta, current_eta, delay_hours, notes, last_occurred_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET
			supplier_id=excluded.supplier_id,
			route_id=excluded.route_id,
			sku=excluded.sku,
			quantity=excluded.quantity,
			status=excluded.status,
			scheduled_eta=excluded.scheduled_eta,
			current_eta=excluded.current_eta,
			delay_hours=excluded.delay_hours,
			notes=excluded.notes,
			last_occurred_at=excluded.last_occurred_at,
			updated_at=excluded.updated_at
	`,
		sh.ID, sh.SupplierID, sh.RouteID, sh.SKU, sh.Quantity, string(sh.Status),
		formatTime(sh.ScheduledETA), formatTime(sh.CurrentETA), sh.DelayHours,
		sh.Notes, formatTimePtr(sh.LastOccurredAt), formatTime(sh.UpdatedAt),
	)
	return err
}

func (s *Store) GetShipment(ctx context.Context, id string) (*domain.Shipment, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, supplier_id, route_id, sku, quantity, status,
		       scheduled_eta, current_eta, delay_hours, notes, last_occurred_at, updated_at
		FROM shipments WHERE id = ?
	`, id)
	var sh domain.Shipment
	var status, sched, cur, updated string
	var lastOcc sql.NullString
	if err := row.Scan(
		&sh.ID, &sh.SupplierID, &sh.RouteID, &sh.SKU, &sh.Quantity, &status,
		&sched, &cur, &sh.DelayHours, &sh.Notes, &lastOcc, &updated,
	); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, fmt.Errorf("%w: shipment %s", domain.ErrNotFound, id)
		}
		return nil, err
	}
	sh.Status = domain.ShipmentStatus(status)
	sh.ScheduledETA = parseTime(sched)
	sh.CurrentETA = parseTime(cur)
	sh.LastOccurredAt = parseTimePtr(lastOcc)
	sh.UpdatedAt = parseTime(updated)
	return &sh, nil
}

func (s *Store) ListShipments(ctx context.Context) ([]domain.Shipment, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, supplier_id, route_id, sku, quantity, status,
		       scheduled_eta, current_eta, delay_hours, notes, last_occurred_at, updated_at
		FROM shipments ORDER BY id ASC
	`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]domain.Shipment, 0)
	for rows.Next() {
		var sh domain.Shipment
		var status, sched, cur, updated string
		var lastOcc sql.NullString
		if err := rows.Scan(
			&sh.ID, &sh.SupplierID, &sh.RouteID, &sh.SKU, &sh.Quantity, &status,
			&sched, &cur, &sh.DelayHours, &sh.Notes, &lastOcc, &updated,
		); err != nil {
			return nil, err
		}
		sh.Status = domain.ShipmentStatus(status)
		sh.ScheduledETA = parseTime(sched)
		sh.CurrentETA = parseTime(cur)
		sh.LastOccurredAt = parseTimePtr(lastOcc)
		sh.UpdatedAt = parseTime(updated)
		out = append(out, sh)
	}
	return out, rows.Err()
}

// --- Inventory ---

func (s *Store) UpsertInventoryItem(ctx context.Context, item domain.InventoryItem) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO inventory_items (
			sku, name, warehouse, primary_supplier_id, on_hand_units,
			daily_burn_rate, safety_stock_days, reorder_point_units,
			lead_time_days, last_occurred_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(sku) DO UPDATE SET
			name=excluded.name,
			warehouse=excluded.warehouse,
			primary_supplier_id=excluded.primary_supplier_id,
			on_hand_units=excluded.on_hand_units,
			daily_burn_rate=excluded.daily_burn_rate,
			safety_stock_days=excluded.safety_stock_days,
			reorder_point_units=excluded.reorder_point_units,
			lead_time_days=excluded.lead_time_days,
			last_occurred_at=excluded.last_occurred_at,
			updated_at=excluded.updated_at
	`,
		item.SKU, item.Name, item.Warehouse, item.PrimarySupplierID, item.OnHandUnits,
		item.DailyBurnRate, item.SafetyStockDays, item.ReorderPointUnits,
		item.LeadTimeDays, formatTimePtr(item.LastOccurredAt), formatTime(item.UpdatedAt),
	)
	return err
}

func (s *Store) GetInventoryItem(ctx context.Context, sku string) (*domain.InventoryItem, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT sku, name, warehouse, primary_supplier_id, on_hand_units,
		       daily_burn_rate, safety_stock_days, reorder_point_units,
		       lead_time_days, last_occurred_at, updated_at
		FROM inventory_items WHERE sku = ?
	`, sku)
	var item domain.InventoryItem
	var updated string
	var lastOcc sql.NullString
	if err := row.Scan(
		&item.SKU, &item.Name, &item.Warehouse, &item.PrimarySupplierID, &item.OnHandUnits,
		&item.DailyBurnRate, &item.SafetyStockDays, &item.ReorderPointUnits,
		&item.LeadTimeDays, &lastOcc, &updated,
	); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, fmt.Errorf("%w: inventory sku %s", domain.ErrNotFound, sku)
		}
		return nil, err
	}
	item.LastOccurredAt = parseTimePtr(lastOcc)
	item.UpdatedAt = parseTime(updated)
	return &item, nil
}

func (s *Store) ListInventoryItems(ctx context.Context) ([]domain.InventoryItem, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT sku, name, warehouse, primary_supplier_id, on_hand_units,
		       daily_burn_rate, safety_stock_days, reorder_point_units,
		       lead_time_days, last_occurred_at, updated_at
		FROM inventory_items ORDER BY sku ASC
	`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]domain.InventoryItem, 0)
	for rows.Next() {
		var item domain.InventoryItem
		var updated string
		var lastOcc sql.NullString
		if err := rows.Scan(
			&item.SKU, &item.Name, &item.Warehouse, &item.PrimarySupplierID, &item.OnHandUnits,
			&item.DailyBurnRate, &item.SafetyStockDays, &item.ReorderPointUnits,
			&item.LeadTimeDays, &lastOcc, &updated,
		); err != nil {
			return nil, err
		}
		item.LastOccurredAt = parseTimePtr(lastOcc)
		item.UpdatedAt = parseTime(updated)
		out = append(out, item)
	}
	return out, rows.Err()
}

// --- Idempotency & Supply Events ---

func (s *Store) TryClaimEvent(ctx context.Context, ev domain.SupplyEvent) (bool, error) {
	res, err := s.db.ExecContext(ctx, `
		INSERT OR IGNORE INTO processed_events (
			event_id, event_type, entity_id, occurred_at, received_at, status, out_of_order
		) VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', 0)
	`,
		ev.EventID, string(ev.EventType), ev.EntityID,
		formatTime(ev.OccurredAt), formatTime(ev.ReceivedAt),
	)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

func (s *Store) CompleteEventClaim(ctx context.Context, eventID string, status domain.ProcessingStatus, outOfOrder bool) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE processed_events SET status = ?, out_of_order = ? WHERE event_id = ?
	`, string(status), boolToInt(outOfOrder), eventID)
	return err
}

func (s *Store) ResetDLQEventClaim(ctx context.Context, eventID string) error {
	_, err := s.db.ExecContext(ctx, `
		DELETE FROM processed_events WHERE event_id = ? AND status = 'DLQ'
	`, eventID)
	return err
}

func (s *Store) AppendSupplyEvent(ctx context.Context, ev domain.SupplyEvent) error {
	payloadJSON, _ := json.Marshal(ev.Payload)
	_, err := s.db.ExecContext(ctx, `
		INSERT OR REPLACE INTO supply_events (
			event_id, event_type, entity_id, entity_type, occurred_at, received_at,
			payload_json, notes, version, correlation_id, processing_status, out_of_order
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`,
		ev.EventID, string(ev.EventType), ev.EntityID, ev.EntityType,
		formatTime(ev.OccurredAt), formatTime(ev.ReceivedAt),
		string(payloadJSON), ev.Notes, ev.Version, ev.CorrelationID,
		string(ev.ProcessingStatus), boolToInt(ev.OutOfOrder),
	)
	return err
}

func (s *Store) ListSupplyEvents(ctx context.Context, limit int) ([]domain.SupplyEvent, error) {
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT event_id, event_type, entity_id, entity_type, occurred_at, received_at,
		       payload_json, notes, version, correlation_id, processing_status, out_of_order
		FROM supply_events ORDER BY occurred_at ASC LIMIT ?
	`, limit)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]domain.SupplyEvent, 0)
	for rows.Next() {
		var ev domain.SupplyEvent
		var et, occ, rec, pJSON, status string
		var ooo int
		if err := rows.Scan(
			&ev.EventID, &et, &ev.EntityID, &ev.EntityType, &occ, &rec,
			&pJSON, &ev.Notes, &ev.Version, &ev.CorrelationID, &status, &ooo,
		); err != nil {
			return nil, err
		}
		ev.EventType = domain.EventType(et)
		ev.OccurredAt = parseTime(occ)
		ev.ReceivedAt = parseTime(rec)
		ev.ProcessingStatus = domain.ProcessingStatus(status)
		ev.OutOfOrder = ooo != 0
		_ = json.Unmarshal([]byte(pJSON), &ev.Payload)
		out = append(out, ev)
	}
	return out, rows.Err()
}

// --- Incidents ---

func (s *Store) UpsertIncident(ctx context.Context, inc domain.CorrelatedIncident) error {
	raw, err := json.Marshal(inc)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `
		INSERT INTO incidents (id, title, status, severity, root_supplier_id, risk_score, payload_json, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET
			title=excluded.title,
			status=excluded.status,
			severity=excluded.severity,
			root_supplier_id=excluded.root_supplier_id,
			risk_score=excluded.risk_score,
			payload_json=excluded.payload_json,
			updated_at=excluded.updated_at
	`,
		inc.ID, inc.Title, string(inc.Status), string(inc.Severity), inc.RootSupplierID,
		inc.RiskScore, string(raw), formatTime(inc.CreatedAt), formatTime(inc.UpdatedAt),
	)
	return err
}

func (s *Store) GetIncident(ctx context.Context, id string) (*domain.CorrelatedIncident, error) {
	var raw string
	err := s.db.QueryRowContext(ctx, `SELECT payload_json FROM incidents WHERE id = ?`, id).Scan(&raw)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, fmt.Errorf("%w: incident %s", domain.ErrNotFound, id)
		}
		return nil, err
	}
	var inc domain.CorrelatedIncident
	if err := json.Unmarshal([]byte(raw), &inc); err != nil {
		return nil, err
	}
	return &inc, nil
}

func (s *Store) ListIncidents(ctx context.Context) ([]domain.CorrelatedIncident, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT payload_json FROM incidents ORDER BY risk_score DESC, id ASC`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]domain.CorrelatedIncident, 0)
	for rows.Next() {
		var raw string
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var inc domain.CorrelatedIncident
		if err := json.Unmarshal([]byte(raw), &inc); err != nil {
			return nil, err
		}
		out = append(out, inc)
	}
	return out, rows.Err()
}

// --- Change Proposals ---

func (s *Store) CreateProposal(ctx context.Context, p domain.ChangeProposal) error {
	paramsJSON, _ := json.Marshal(p.Parameters)
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO change_proposals (
			id, incident_id, proposal_type, target_entity_id, parameters_json,
			summary, rationale, proposed_by, proposed_by_type, status,
			approved_by, approved_by_type, approval_comment,
			rejected_by, rejected_reason, executed_by, executed_at,
			created_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`,
		p.ID, p.IncidentID, string(p.ProposalType), p.TargetEntityID, string(paramsJSON),
		p.Summary, p.Rationale, p.ProposedBy, string(p.ProposedByType), string(p.Status),
		p.ApprovedBy, string(p.ApprovedByType), p.ApprovalComment,
		p.RejectedBy, p.RejectedReason, p.ExecutedBy, formatTimePtr(p.ExecutedAt),
		formatTime(p.CreatedAt), formatTime(p.UpdatedAt),
	)
	return err
}

func (s *Store) UpdateProposal(ctx context.Context, p domain.ChangeProposal) error {
	paramsJSON, _ := json.Marshal(p.Parameters)
	res, err := s.db.ExecContext(ctx, `
		UPDATE change_proposals SET
			parameters_json = ?,
			summary = ?,
			rationale = ?,
			status = ?,
			approved_by = ?,
			approved_by_type = ?,
			approval_comment = ?,
			rejected_by = ?,
			rejected_reason = ?,
			executed_by = ?,
			executed_at = ?,
			updated_at = ?
		WHERE id = ?
	`,
		string(paramsJSON), p.Summary, p.Rationale, string(p.Status),
		p.ApprovedBy, string(p.ApprovedByType), p.ApprovalComment,
		p.RejectedBy, p.RejectedReason, p.ExecutedBy, formatTimePtr(p.ExecutedAt),
		formatTime(p.UpdatedAt), p.ID,
	)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return fmt.Errorf("%w: proposal %s", domain.ErrNotFound, p.ID)
	}
	return nil
}

func scanProposal(scanner interface{ Scan(...any) error }) (*domain.ChangeProposal, error) {
	var p domain.ChangeProposal
	var pType, paramsJSON, propByType, status, appByType, created, updated string
	var execAt sql.NullString
	if err := scanner.Scan(
		&p.ID, &p.IncidentID, &pType, &p.TargetEntityID, &paramsJSON,
		&p.Summary, &p.Rationale, &p.ProposedBy, &propByType, &status,
		&p.ApprovedBy, &appByType, &p.ApprovalComment,
		&p.RejectedBy, &p.RejectedReason, &p.ExecutedBy, &execAt,
		&created, &updated,
	); err != nil {
		return nil, err
	}
	p.ProposalType = domain.ProposalType(pType)
	p.ProposedByType = domain.ActorType(propByType)
	p.Status = domain.ProposalStatus(status)
	p.ApprovedByType = domain.ActorType(appByType)
	p.ExecutedAt = parseTimePtr(execAt)
	p.CreatedAt = parseTime(created)
	p.UpdatedAt = parseTime(updated)
	_ = json.Unmarshal([]byte(paramsJSON), &p.Parameters)
	return &p, nil
}

func (s *Store) GetProposal(ctx context.Context, id string) (*domain.ChangeProposal, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, incident_id, proposal_type, target_entity_id, parameters_json,
		       summary, rationale, proposed_by, proposed_by_type, status,
		       approved_by, approved_by_type, approval_comment,
		       rejected_by, rejected_reason, executed_by, executed_at,
		       created_at, updated_at
		FROM change_proposals WHERE id = ?
	`, id)
	p, err := scanProposal(row)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, fmt.Errorf("%w: proposal %s", domain.ErrNotFound, id)
		}
		return nil, err
	}
	return p, nil
}

func (s *Store) ListProposals(ctx context.Context) ([]domain.ChangeProposal, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, incident_id, proposal_type, target_entity_id, parameters_json,
		       summary, rationale, proposed_by, proposed_by_type, status,
		       approved_by, approved_by_type, approval_comment,
		       rejected_by, rejected_reason, executed_by, executed_at,
		       created_at, updated_at
		FROM change_proposals ORDER BY created_at DESC
	`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]domain.ChangeProposal, 0)
	for rows.Next() {
		p, err := scanProposal(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *p)
	}
	return out, rows.Err()
}

// --- DLQ ---

func (s *Store) InsertDLQ(ctx context.Context, dlq domain.DeadLetterEvent) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO dead_letter_events (
			id, event_id, event_type, entity_id, raw_payload, error_reason,
			retry_count, replayed, replayed_at, created_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`,
		dlq.ID, dlq.EventID, string(dlq.EventType), dlq.EntityID, dlq.RawPayload,
		dlq.ErrorReason, dlq.RetryCount, boolToInt(dlq.Replayed),
		formatTimePtr(dlq.ReplayedAt), formatTime(dlq.CreatedAt),
	)
	return err
}

func (s *Store) GetDLQ(ctx context.Context, id string) (*domain.DeadLetterEvent, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, event_id, event_type, entity_id, raw_payload, error_reason,
		       retry_count, replayed, replayed_at, created_at
		FROM dead_letter_events WHERE id = ?
	`, id)
	var dlq domain.DeadLetterEvent
	var et, created string
	var replayed int
	var replayedAt sql.NullString
	if err := row.Scan(
		&dlq.ID, &dlq.EventID, &et, &dlq.EntityID, &dlq.RawPayload, &dlq.ErrorReason,
		&dlq.RetryCount, &replayed, &replayedAt, &created,
	); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, fmt.Errorf("%w: dlq event %s", domain.ErrNotFound, id)
		}
		return nil, err
	}
	dlq.EventType = domain.EventType(et)
	dlq.Replayed = replayed != 0
	dlq.ReplayedAt = parseTimePtr(replayedAt)
	dlq.CreatedAt = parseTime(created)
	return &dlq, nil
}

func (s *Store) ListDLQ(ctx context.Context) ([]domain.DeadLetterEvent, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, event_id, event_type, entity_id, raw_payload, error_reason,
		       retry_count, replayed, replayed_at, created_at
		FROM dead_letter_events ORDER BY created_at DESC
	`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]domain.DeadLetterEvent, 0)
	for rows.Next() {
		var dlq domain.DeadLetterEvent
		var et, created string
		var replayed int
		var replayedAt sql.NullString
		if err := rows.Scan(
			&dlq.ID, &dlq.EventID, &et, &dlq.EntityID, &dlq.RawPayload, &dlq.ErrorReason,
			&dlq.RetryCount, &replayed, &replayedAt, &created,
		); err != nil {
			return nil, err
		}
		dlq.EventType = domain.EventType(et)
		dlq.Replayed = replayed != 0
		dlq.ReplayedAt = parseTimePtr(replayedAt)
		dlq.CreatedAt = parseTime(created)
		out = append(out, dlq)
	}
	return out, rows.Err()
}

func (s *Store) MarkDLQReplayed(ctx context.Context, id string, replayedAt time.Time) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE dead_letter_events SET replayed = 1, replayed_at = ? WHERE id = ?
	`, formatTime(replayedAt), id)
	return err
}

// --- Alerts & Audit ---

func (s *Store) InsertAlert(ctx context.Context, alert domain.AlertNotification) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO alert_notifications (
			id, incident_id, severity, title, message, channel, delivered, error, created_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
	`,
		alert.ID, alert.IncidentID, string(alert.Severity), alert.Title, alert.Message,
		alert.Channel, boolToInt(alert.Delivered), alert.Error, formatTime(alert.CreatedAt),
	)
	return err
}

func (s *Store) ListAlerts(ctx context.Context, limit int) ([]domain.AlertNotification, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, incident_id, severity, title, message, channel, delivered, error, created_at
		FROM alert_notifications ORDER BY created_at DESC LIMIT ?
	`, limit)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]domain.AlertNotification, 0)
	for rows.Next() {
		var a domain.AlertNotification
		var sev, created string
		var delivered int
		if err := rows.Scan(
			&a.ID, &a.IncidentID, &sev, &a.Title, &a.Message,
			&a.Channel, &delivered, &a.Error, &created,
		); err != nil {
			return nil, err
		}
		a.Severity = domain.Severity(sev)
		a.Delivered = delivered != 0
		a.CreatedAt = parseTime(created)
		out = append(out, a)
	}
	return out, rows.Err()
}

func (s *Store) InsertAudit(ctx context.Context, entry domain.AuditEntry) error {
	detailsJSON, _ := json.Marshal(entry.Details)
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO audit_logs (
			id, correlation_id, actor, actor_type, action, resource_type,
			resource_id, outcome, details_json, created_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`,
		entry.ID, entry.CorrelationID, entry.Actor, string(entry.ActorType),
		entry.Action, entry.ResourceType, entry.ResourceID, entry.Outcome,
		string(detailsJSON), formatTime(entry.CreatedAt),
	)
	return err
}

func (s *Store) ListAudit(ctx context.Context, limit int) ([]domain.AuditEntry, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, correlation_id, actor, actor_type, action, resource_type,
		       resource_id, outcome, details_json, created_at
		FROM audit_logs ORDER BY created_at DESC LIMIT ?
	`, limit)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]domain.AuditEntry, 0)
	for rows.Next() {
		var a domain.AuditEntry
		var actorType, detailsJSON, created string
		if err := rows.Scan(
			&a.ID, &a.CorrelationID, &a.Actor, &actorType, &a.Action,
			&a.ResourceType, &a.ResourceID, &a.Outcome, &detailsJSON, &created,
		); err != nil {
			return nil, err
		}
		a.ActorType = domain.ActorType(actorType)
		a.CreatedAt = parseTime(created)
		_ = json.Unmarshal([]byte(detailsJSON), &a.Details)
		out = append(out, a)
	}
	return out, rows.Err()
}
