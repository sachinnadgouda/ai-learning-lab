package sqlite

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

// ProcessEventAtomic executes the idempotency check, entity state mutation, supply_events append,
// and final processed_events claim status inside a single SQLite transaction.
// Any error at any persistence boundary rolls back the transaction completely so no partial state
// or dangling IN_PROGRESS claim can persist and the event remains recoverable.
func (s *Store) ProcessEventAtomic(
	ctx context.Context,
	ev domain.SupplyEvent,
	applyFn func(ctx context.Context, tx application.EventApplyTx) (outOfOrder bool, err error),
) (application.AtomicProcessResult, error) {
	if err := s.injectFault("begin_tx", ev.EventID); err != nil {
		return application.AtomicProcessResult{}, err
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return application.AtomicProcessResult{}, fmt.Errorf("begin event tx: %w", err)
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback()
		}
	}()

	if err := s.injectFault("check_claim", ev.EventID); err != nil {
		return application.AtomicProcessResult{}, err
	}

	var existingStatus string
	var existingOOO int
	err = tx.QueryRowContext(ctx, `
		SELECT status, out_of_order FROM processed_events WHERE event_id = ?
	`, ev.EventID).Scan(&existingStatus, &existingOOO)
	if err == nil {
		// Terminal claim already committed (PROCESSED, OUT_OF_ORDER, or DLQ): do not reapply mutation.
		if existingStatus != string(domain.ProcessingInProgress) {
			return application.AtomicProcessResult{
				Duplicate:  true,
				OutOfOrder: existingOOO != 0,
				Status:     domain.ProcessingStatus(existingStatus),
			}, nil
		}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return application.AtomicProcessResult{}, fmt.Errorf("query processed_events: %w", err)
	} else {
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO processed_events (
				event_id, event_type, entity_id, occurred_at, received_at, status, out_of_order
			) VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', 0)
		`,
			ev.EventID, string(ev.EventType), ev.EntityID,
			formatTime(ev.OccurredAt), formatTime(ev.ReceivedAt),
		); err != nil {
			return application.AtomicProcessResult{}, fmt.Errorf("insert processed_events claim: %w", err)
		}
	}

	txView := &eventApplyTx{
		tx:      tx,
		store:   s,
		eventID: ev.EventID,
	}

	outOfOrder, err := applyFn(ctx, txView)
	if err != nil {
		return application.AtomicProcessResult{}, err
	}

	finalStatus := domain.ProcessingProcessed
	if outOfOrder {
		finalStatus = domain.ProcessingOutOfOrder
	}
	ev.ProcessingStatus = finalStatus
	ev.OutOfOrder = outOfOrder

	if err := s.injectFault("append_event", ev.EventID); err != nil {
		return application.AtomicProcessResult{}, err
	}
	payloadJSON, _ := json.Marshal(ev.Payload)
	if _, err := tx.ExecContext(ctx, `
		INSERT OR REPLACE INTO supply_events (
			event_id, event_type, entity_id, entity_type, occurred_at, received_at,
			payload_json, notes, version, correlation_id, processing_status, out_of_order
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`,
		ev.EventID, string(ev.EventType), ev.EntityID, ev.EntityType,
		formatTime(ev.OccurredAt), formatTime(ev.ReceivedAt),
		string(payloadJSON), ev.Notes, ev.Version, ev.CorrelationID,
		string(ev.ProcessingStatus), boolToInt(ev.OutOfOrder),
	); err != nil {
		return application.AtomicProcessResult{}, fmt.Errorf("append supply_events: %w", err)
	}

	if err := s.injectFault("complete_claim", ev.EventID); err != nil {
		return application.AtomicProcessResult{}, err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE processed_events SET status = ?, out_of_order = ? WHERE event_id = ?
	`, string(finalStatus), boolToInt(outOfOrder), ev.EventID); err != nil {
		return application.AtomicProcessResult{}, fmt.Errorf("complete processed_events claim: %w", err)
	}

	if err := s.injectFault("commit_tx", ev.EventID); err != nil {
		return application.AtomicProcessResult{}, err
	}
	if err := tx.Commit(); err != nil {
		return application.AtomicProcessResult{}, fmt.Errorf("commit event tx: %w", err)
	}
	committed = true

	return application.AtomicProcessResult{
		Duplicate:  false,
		OutOfOrder: outOfOrder,
		Status:     finalStatus,
	}, nil
}

// RecordEventDLQAtomic atomically records the DLQ entry and marks the event's idempotency claim as DLQ.
func (s *Store) RecordEventDLQAtomic(ctx context.Context, ev domain.SupplyEvent, dlq domain.DeadLetterEvent) error {
	if err := s.injectFault("dlq_claim", dlq.EventID); err != nil {
		return err
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin dlq tx: %w", err)
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback()
		}
	}()

	if ev.EventID != "" {
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO processed_events (
				event_id, event_type, entity_id, occurred_at, received_at, status, out_of_order
			) VALUES (?, ?, ?, ?, ?, 'DLQ', 0)
			ON CONFLICT(event_id) DO UPDATE SET status = 'DLQ', out_of_order = 0
		`,
			ev.EventID, string(ev.EventType), ev.EntityID,
			formatTime(ev.OccurredAt), formatTime(ev.ReceivedAt),
		); err != nil {
			return fmt.Errorf("mark event claim DLQ: %w", err)
		}
	}

	if err := s.injectFault("dlq_insert", dlq.EventID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO dead_letter_events (
			id, event_id, event_type, entity_id, raw_payload, error_reason,
			retry_count, replayed, replayed_at, created_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`,
		dlq.ID, dlq.EventID, string(dlq.EventType), dlq.EntityID, dlq.RawPayload,
		dlq.ErrorReason, dlq.RetryCount, boolToInt(dlq.Replayed),
		formatTimePtr(dlq.ReplayedAt), formatTime(dlq.CreatedAt),
	); err != nil {
		return fmt.Errorf("insert dead_letter_events: %w", err)
	}

	if err := s.injectFault("dlq_commit", dlq.EventID); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit dlq tx: %w", err)
	}
	committed = true
	return nil
}

// GetEventClaimStatus returns the committed processed_events status for an event_id (used in tests & diagnostics).
func (s *Store) GetEventClaimStatus(ctx context.Context, eventID string) (domain.ProcessingStatus, bool, error) {
	var status string
	err := s.db.QueryRowContext(ctx, `SELECT status FROM processed_events WHERE event_id = ?`, eventID).Scan(&status)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return domain.ProcessingStatus(status), true, nil
}

// eventApplyTx implements application.EventApplyTx bound to a single *sql.Tx.
type eventApplyTx struct {
	tx      *sql.Tx
	store   *Store
	eventID string
}

func (t *eventApplyTx) GetSupplier(ctx context.Context, id string) (*domain.Supplier, error) {
	if err := t.store.injectFault("read_entity", t.eventID); err != nil {
		return nil, err
	}
	row := t.tx.QueryRowContext(ctx, `
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

func (t *eventApplyTx) UpsertSupplier(ctx context.Context, sup domain.Supplier) error {
	if err := t.store.injectFault("mutate_entity", t.eventID); err != nil {
		return err
	}
	_, err := t.tx.ExecContext(ctx, `
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

func (t *eventApplyTx) GetRoute(ctx context.Context, id string) (*domain.Route, error) {
	if err := t.store.injectFault("read_entity", t.eventID); err != nil {
		return nil, err
	}
	row := t.tx.QueryRowContext(ctx, `
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

func (t *eventApplyTx) UpsertRoute(ctx context.Context, r domain.Route) error {
	if err := t.store.injectFault("mutate_entity", t.eventID); err != nil {
		return err
	}
	_, err := t.tx.ExecContext(ctx, `
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

func (t *eventApplyTx) GetShipment(ctx context.Context, id string) (*domain.Shipment, error) {
	if err := t.store.injectFault("read_entity", t.eventID); err != nil {
		return nil, err
	}
	row := t.tx.QueryRowContext(ctx, `
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

func (t *eventApplyTx) UpsertShipment(ctx context.Context, sh domain.Shipment) error {
	if err := t.store.injectFault("mutate_entity", t.eventID); err != nil {
		return err
	}
	_, err := t.tx.ExecContext(ctx, `
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

func (t *eventApplyTx) GetInventoryItem(ctx context.Context, sku string) (*domain.InventoryItem, error) {
	if err := t.store.injectFault("read_entity", t.eventID); err != nil {
		return nil, err
	}
	row := t.tx.QueryRowContext(ctx, `
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

func (t *eventApplyTx) UpsertInventoryItem(ctx context.Context, item domain.InventoryItem) error {
	if err := t.store.injectFault("mutate_entity", t.eventID); err != nil {
		return err
	}
	_, err := t.tx.ExecContext(ctx, `
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
