package application

import (
	"context"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

// SeedDataset holds a realistic global supply chain dataset for demos, simulator, and --seed mode.
type SeedDataset struct {
	Suppliers []domain.Supplier
	Routes    []domain.Route
	Shipments []domain.Shipment
	Inventory []domain.InventoryItem
}

func DefaultSeedDataset(now time.Time) SeedDataset {
	ts := now.UTC()
	return SeedDataset{
		Suppliers: []domain.Supplier{
			{
				ID: "SUP-TW-SEMI", Name: "Formosa Precision Silicon", Region: "APAC", Category: "semiconductors",
				Tier: 1, SingleSource: true, HistoricalDelayRatio: 0.22, ActiveDisruptionSeverity: 10,
				Status: domain.SupplierOperational, LeadTimeDays: 14, UnitCostUSD: 14.50, UpdatedAt: ts,
			},
			{
				ID: "SUP-DE-SEMI", Name: "Saxony Microelectronics GmbH", Region: "EU", Category: "semiconductors",
				Tier: 1, SingleSource: false, HistoricalDelayRatio: 0.06, ActiveDisruptionSeverity: 0,
				Status: domain.SupplierOperational, LeadTimeDays: 9, UnitCostUSD: 16.40, BackupForID: "SUP-TW-SEMI", UpdatedAt: ts,
			},
			{
				ID: "SUP-CN-BATT", Name: "Shenzhen VoltCore Cells", Region: "APAC", Category: "battery_cells",
				Tier: 1, SingleSource: false, HistoricalDelayRatio: 0.18, ActiveDisruptionSeverity: 5,
				Status: domain.SupplierOperational, LeadTimeDays: 18, UnitCostUSD: 42.00, UpdatedAt: ts,
			},
			{
				ID: "SUP-PL-BATT", Name: "Wroclaw Energy Pack Sp.", Region: "EU", Category: "battery_cells",
				Tier: 2, SingleSource: false, HistoricalDelayRatio: 0.09, ActiveDisruptionSeverity: 0,
				Status: domain.SupplierOperational, LeadTimeDays: 10, UnitCostUSD: 47.50, BackupForID: "SUP-CN-BATT", UpdatedAt: ts,
			},
		},
		Routes: []domain.Route{
			{
				ID: "RT-OCEAN-TW-RTM", Name: "Kaohsiung to Rotterdam Ocean", Origin: "Kaohsiung", Destination: "Rotterdam",
				Mode: "OCEAN", BaseTransitDays: 28, TransitDelayVarianceDays: 2.5, CongestionSeverity: 20,
				WeatherIncidentCount: 0, Status: domain.RouteClear, CostMultiplier: 1.0, UpdatedAt: ts,
			},
			{
				ID: "RT-AIR-TW-FRA", Name: "Taipei to Frankfurt Air Freight", Origin: "Kaohsiung", Destination: "Rotterdam",
				Mode: "AIR", BaseTransitDays: 4, TransitDelayVarianceDays: 0.5, CongestionSeverity: 8,
				WeatherIncidentCount: 0, Status: domain.RouteClear, CostMultiplier: 2.35, BackupForID: "RT-OCEAN-TW-RTM", UpdatedAt: ts,
			},
			{
				ID: "RT-OCEAN-CN-HAM", Name: "Ningbo to Hamburg Ocean", Origin: "Ningbo", Destination: "Hamburg",
				Mode: "OCEAN", BaseTransitDays: 30, TransitDelayVarianceDays: 3.0, CongestionSeverity: 15,
				WeatherIncidentCount: 0, Status: domain.RouteClear, CostMultiplier: 1.0, UpdatedAt: ts,
			},
			{
				ID: "RT-RAIL-CN-DUI", Name: "Chongqing to Duisburg Rail", Origin: "Ningbo", Destination: "Hamburg",
				Mode: "RAIL", BaseTransitDays: 16, TransitDelayVarianceDays: 1.5, CongestionSeverity: 12,
				WeatherIncidentCount: 0, Status: domain.RouteClear, CostMultiplier: 1.55, BackupForID: "RT-OCEAN-CN-HAM", UpdatedAt: ts,
			},
		},
		Shipments: []domain.Shipment{
			{
				ID: "SHP-1001", SupplierID: "SUP-TW-SEMI", RouteID: "RT-OCEAN-TW-RTM", SKU: "SKU-MCU-01",
				Quantity: 2500, Status: domain.ShipmentInTransit,
				ScheduledETA: ts.Add(4 * 24 * time.Hour), CurrentETA: ts.Add(4 * 24 * time.Hour),
				DelayHours: 0, Notes: "MV Northern Star container #MSCU-48120", UpdatedAt: ts,
			},
			{
				ID: "SHP-1002", SupplierID: "SUP-TW-SEMI", RouteID: "RT-OCEAN-TW-RTM", SKU: "SKU-PMIC-02",
				Quantity: 1800, Status: domain.ShipmentInTransit,
				ScheduledETA: ts.Add(5 * 24 * time.Hour), CurrentETA: ts.Add(5 * 24 * time.Hour),
				DelayHours: 0, Notes: "MV Northern Star container #MSCU-48121", UpdatedAt: ts,
			},
			{
				ID: "SHP-2001", SupplierID: "SUP-CN-BATT", RouteID: "RT-OCEAN-CN-HAM", SKU: "SKU-CELL-03",
				Quantity: 1200, Status: domain.ShipmentInTransit,
				ScheduledETA: ts.Add(6 * 24 * time.Hour), CurrentETA: ts.Add(6 * 24 * time.Hour),
				DelayHours: 0, Notes: "MV Baltic Carrier hazmat bay 4", UpdatedAt: ts,
			},
			{
				ID: "SHP-3001", SupplierID: "SUP-DE-SEMI", RouteID: "RT-AIR-TW-FRA", SKU: "SKU-CTRL-04",
				Quantity: 900, Status: domain.ShipmentScheduled,
				ScheduledETA: ts.Add(3 * 24 * time.Hour), CurrentETA: ts.Add(3 * 24 * time.Hour),
				DelayHours: 0, Notes: "Standard EU replenishment", UpdatedAt: ts,
			},
		},
		Inventory: []domain.InventoryItem{
			{
				SKU: "SKU-MCU-01", Name: "32-bit Automotive Traction MCU", Warehouse: "Munich-Plant-WH1",
				PrimarySupplierID: "SUP-TW-SEMI", OnHandUnits: 720, DailyBurnRate: 140,
				SafetyStockDays: 10, ReorderPointUnits: 1600, LeadTimeDays: 14, UpdatedAt: ts,
			},
			{
				SKU: "SKU-PMIC-02", Name: "High-Voltage Power Management IC", Warehouse: "Munich-Plant-WH1",
				PrimarySupplierID: "SUP-TW-SEMI", OnHandUnits: 950, DailyBurnRate: 110,
				SafetyStockDays: 9, ReorderPointUnits: 1200, LeadTimeDays: 14, UpdatedAt: ts,
			},
			{
				SKU: "SKU-CELL-03", Name: "LFP Prismatic Battery Module", Warehouse: "Stuttgart-Pack-WH2",
				PrimarySupplierID: "SUP-CN-BATT", OnHandUnits: 1800, DailyBurnRate: 120,
				SafetyStockDays: 10, ReorderPointUnits: 1500, LeadTimeDays: 18, UpdatedAt: ts,
			},
			{
				SKU: "SKU-CTRL-04", Name: "Safety Interlock Controller", Warehouse: "Munich-Plant-WH1",
				PrimarySupplierID: "SUP-DE-SEMI", OnHandUnits: 2400, DailyBurnRate: 60,
				SafetyStockDays: 12, ReorderPointUnits: 900, LeadTimeDays: 9, UpdatedAt: ts,
			},
		},
	}
}

// SeedRepository loads the default dataset into the repository idempotently.
func SeedRepository(ctx context.Context, repo Repository, now time.Time) error {
	ds := DefaultSeedDataset(now)
	for _, s := range ds.Suppliers {
		if err := repo.UpsertSupplier(ctx, s); err != nil {
			return err
		}
	}
	for _, r := range ds.Routes {
		if err := repo.UpsertRoute(ctx, r); err != nil {
			return err
		}
	}
	for _, sh := range ds.Shipments {
		if err := repo.UpsertShipment(ctx, sh); err != nil {
			return err
		}
	}
	for _, it := range ds.Inventory {
		if err := repo.UpsertInventoryItem(ctx, it); err != nil {
			return err
		}
	}
	return nil
}

// BuildDemoCascadeEvents constructs a realistic event sequence covering normal consumption,
// a typhoon port-congestion + supplier-disruption cascade, duplicate delivery, and out-of-order replay.
func BuildDemoCascadeEvents(base time.Time) []domain.SupplyEvent {
	t0 := base.UTC()
	return []domain.SupplyEvent{
		{
			EventID:    "sim-ev-001",
			EventType:  domain.EventInventoryConsumed,
			EntityID:   "SKU-MCU-01",
			OccurredAt: t0.Add(-5 * time.Hour),
			Payload:    map[string]any{"units": 140},
			Notes:      "Normal shift A production draw at Munich-Plant-WH1",
			Version:    1,
		},
		{
			EventID:    "sim-ev-002",
			EventType:  domain.EventRouteCongestion,
			EntityID:   "RT-OCEAN-TW-RTM",
			OccurredAt: t0.Add(-4 * time.Hour),
			Payload:    map[string]any{"congestion_severity": 86, "delay_variance_days": 8.5, "weather_incidents": 2},
			Notes:      "Severe berth congestion at Kaohsiung following typhoon warning; 19 vessels anchored.",
			Version:    1,
		},
		{
			EventID:    "sim-ev-003",
			EventType:  domain.EventSupplierDisruption,
			EntityID:   "SUP-TW-SEMI",
			OccurredAt: t0.Add(-3 * time.Hour),
			Payload:    map[string]any{"severity": 88, "reason": "substation_outage"},
			Notes:      "Wafer fab line 2 halted due to regional grid disturbance; recovery estimate 7 days.",
			Version:    1,
		},
		{
			EventID:    "sim-ev-004",
			EventType:  domain.EventShipmentDelayed,
			EntityID:   "SHP-1001",
			OccurredAt: t0.Add(-2 * time.Hour),
			Payload:    map[string]any{"delay_hours": 168},
			Notes:      "Carrier advisory: MV Northern Star rolled to next sailing (+7 days ETA).",
			Version:    1,
		},
		// Duplicate of sim-ev-004 (must be idempotently skipped without double-adding 168h delay!)
		{
			EventID:    "sim-ev-004",
			EventType:  domain.EventShipmentDelayed,
			EntityID:   "SHP-1001",
			OccurredAt: t0.Add(-2 * time.Hour),
			Payload:    map[string]any{"delay_hours": 168},
			Notes:      "Duplicate carrier webhook retry for SHP-1001",
			Version:    1,
		},
		{
			EventID:    "sim-ev-005",
			EventType:  domain.EventInventoryConsumed,
			EntityID:   "SKU-MCU-01",
			OccurredAt: t0.Add(-1 * time.Hour),
			Payload:    map[string]any{"units": 210},
			Notes:      "High production draw ahead of weekend build",
			Version:    1,
		},
		// Out-of-order historical event for SUP-TW-SEMI (earlier OccurredAt than sim-ev-003 -> must NOT overwrite disrupted state!)
		{
			EventID:    "sim-ev-006-ooo",
			EventType:  domain.EventSupplierRecovered,
			EntityID:   "SUP-TW-SEMI",
			OccurredAt: t0.Add(-6 * time.Hour),
			Payload:    map[string]any{"severity": 0},
			Notes:      "Stale recovery telemetry from prior minor maintenance window (out-of-order delivery)",
			Version:    1,
		},
	}
}
