package domain

import (
	"testing"
	"time"
)

func TestScoreSupplier_DeterministicAndBounded(t *testing.T) {
	eng := NewRiskEngine()
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)

	low := Supplier{
		ID:                       "sup-low",
		Name:                     "Nordic Parts",
		Tier:                     3,
		SingleSource:             false,
		HistoricalDelayRatio:     0.05,
		ActiveDisruptionSeverity: 0,
		Status:                   SupplierOperational,
	}
	lowScore := eng.ScoreSupplier(low, now)
	if lowScore.Severity != SeverityLow || lowScore.Score >= 30 {
		t.Fatalf("expected LOW risk (<30), got %.2f (%s)", lowScore.Score, lowScore.Severity)
	}
	if len(lowScore.Factors) != 4 {
		t.Fatalf("expected 4 risk factors, got %d", len(lowScore.Factors))
	}

	crit := Supplier{
		ID:                       "sup-crit",
		Name:                     "Shenzhen Silicon",
		Tier:                     1,
		SingleSource:             true,
		HistoricalDelayRatio:     0.8,
		ActiveDisruptionSeverity: 90,
		Status:                   SupplierDisrupted,
	}
	critScore := eng.ScoreSupplier(crit, now)
	if critScore.Severity != SeverityCritical || critScore.Score < 75 {
		t.Fatalf("expected CRITICAL risk (>=75), got %.2f (%s)", critScore.Score, critScore.Severity)
	}

	// Verify missing/out-of-range inputs are safely bounded and never panic or NaN.
	weird := Supplier{
		ID:                       "sup-weird",
		Name:                     "Edge Case Corp",
		Tier:                     0,
		HistoricalDelayRatio:     -5.0,
		ActiveDisruptionSeverity: 500.0,
		Status:                   "",
	}
	weirdScore := eng.ScoreSupplier(weird, now)
	if weirdScore.Score < 0 || weirdScore.Score > 100 {
		t.Fatalf("expected clamped score in [0,100], got %.2f", weirdScore.Score)
	}
}

func TestScoreRoute_CongestionAndDelays(t *testing.T) {
	eng := NewRiskEngine()
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)

	clearRoute := Route{
		ID:                       "rt-clear",
		Name:                     "Air Express",
		BaseTransitDays:          3,
		TransitDelayVarianceDays: 0.5,
		CongestionSeverity:       5,
		WeatherIncidentCount:     0,
		Status:                   RouteClear,
	}
	cs := eng.ScoreRoute(clearRoute, 0, now)
	if cs.Severity != SeverityLow {
		t.Fatalf("expected LOW route risk, got %.2f (%s)", cs.Score, cs.Severity)
	}

	congested := Route{
		ID:                       "rt-cong",
		Name:                     "Shanghai-Rotterdam Ocean",
		BaseTransitDays:          28,
		TransitDelayVarianceDays: 8,
		CongestionSeverity:       85,
		WeatherIncidentCount:     3,
		Status:                   RouteCongested,
	}
	hs := eng.ScoreRoute(congested, 4, now)
	if hs.Severity != SeverityCritical || hs.Score < 75 {
		t.Fatalf("expected CRITICAL route risk, got %.2f (%s)", hs.Score, hs.Severity)
	}

	blocked := Route{
		ID:     "rt-block",
		Name:   "Blocked Canal",
		Status: RouteBlocked,
	}
	bs := eng.ScoreRoute(blocked, 0, now)
	if bs.Score < 80 || bs.Severity != SeverityCritical {
		t.Fatalf("expected blocked route >=80 CRITICAL, got %.2f (%s)", bs.Score, bs.Severity)
	}
}

func TestDetectShortages_DaysOfCoverAndDelayedInbound(t *testing.T) {
	eng := NewRiskEngine()
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)

	items := []InventoryItem{
		{
			SKU:               "SKU-MCU-01",
			Name:              "Automotive MCU",
			Warehouse:         "Munich-WH1",
			PrimarySupplierID: "sup-1",
			OnHandUnits:       400,
			DailyBurnRate:     100, // 4 days of cover
			SafetyStockDays:   10,
			ReorderPointUnits: 1200,
		},
		{
			SKU:               "SKU-HEALTHY",
			Name:              "Standard Bracket",
			Warehouse:         "Munich-WH1",
			PrimarySupplierID: "sup-2",
			OnHandUnits:       5000,
			DailyBurnRate:     100, // 50 days of cover
			SafetyStockDays:   10,
			ReorderPointUnits: 1200,
		},
		{
			SKU:               "SKU-ZERO-BURN",
			Name:              "Legacy Spare",
			Warehouse:         "Munich-WH1",
			PrimarySupplierID: "sup-2",
			OnHandUnits:       0,
			DailyBurnRate:     0, // missing/zero burn rate with zero on-hand
			SafetyStockDays:   5,
			ReorderPointUnits: 20,
		},
		{
			SKU:               "SKU-ZERO-BURN-STOCKED",
			Name:              "Static Stock",
			Warehouse:         "Munich-WH1",
			PrimarySupplierID: "sup-2",
			OnHandUnits:       100,
			DailyBurnRate:     0, // should not divide by zero or false alert
			SafetyStockDays:   5,
		},
	}

	shipments := []Shipment{
		{
			ID:           "shp-1",
			SupplierID:   "sup-1",
			RouteID:      "rt-1",
			SKU:          "SKU-MCU-01",
			Quantity:     2000,
			Status:       ShipmentDelayed,
			ScheduledETA: now.Add(3 * 24 * time.Hour),
			CurrentETA:   now.Add(9 * 24 * time.Hour), // arrives 5 days AFTER stockout!
			DelayHours:   144,
		},
	}

	alerts := eng.DetectShortages(items, shipments, now)
	if len(alerts) != 2 {
		t.Fatalf("expected 2 shortage alerts (MCU + zero-on-hand), got %d: %+v", len(alerts), alerts)
	}

	top := alerts[0]
	if top.SKU != "SKU-MCU-01" {
		t.Fatalf("expected SKU-MCU-01 first, got %s", top.SKU)
	}
	if top.Severity != SeverityCritical {
		t.Fatalf("expected CRITICAL severity for SKU-MCU-01, got %s", top.Severity)
	}
	if top.DaysOfCover != 4 {
		t.Fatalf("expected 4.0 days of cover, got %.2f", top.DaysOfCover)
	}
	if top.DelayedInboundUnits != 2000 {
		t.Fatalf("expected 2000 delayed inbound units, got %d", top.DelayedInboundUnits)
	}
	if top.ShortfallUnits < 600 {
		t.Fatalf("expected shortfall >= 600 units, got %d", top.ShortfallUnits)
	}
	if top.ProjectedStockoutDate == nil {
		t.Fatal("expected projected stockout date")
	}
}

func TestCorrelateAndRecommendAlternatives(t *testing.T) {
	eng := NewRiskEngine()
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)

	suppliers := []Supplier{
		{
			ID: "sup-primary", Name: "Taiwan Fab A", Region: "APAC", Category: "semiconductors",
			Tier: 1, SingleSource: true, HistoricalDelayRatio: 0.4, ActiveDisruptionSeverity: 85,
			Status: SupplierDisrupted, LeadTimeDays: 14, UnitCostUSD: 12.0,
		},
		{
			ID: "sup-backup-eu", Name: "Dresden Fab B", Region: "EU", Category: "semiconductors",
			Tier: 1, SingleSource: false, HistoricalDelayRatio: 0.08, ActiveDisruptionSeverity: 0,
			Status: SupplierOperational, LeadTimeDays: 9, UnitCostUSD: 13.2, BackupForID: "sup-primary",
		},
		{
			ID: "sup-backup-slow", Name: "Slow Backup C", Region: "NA", Category: "semiconductors",
			Tier: 2, SingleSource: false, HistoricalDelayRatio: 0.35, ActiveDisruptionSeverity: 20,
			Status: SupplierDegraded, LeadTimeDays: 21, UnitCostUSD: 15.0, BackupForID: "sup-primary",
		},
	}

	routes := []Route{
		{
			ID: "rt-ocean", Name: "Kaohsiung-Rotterdam Ocean", Origin: "Kaohsiung", Destination: "Rotterdam",
			Mode: "OCEAN", BaseTransitDays: 28, TransitDelayVarianceDays: 7, CongestionSeverity: 80,
			WeatherIncidentCount: 2, Status: RouteCongested, CostMultiplier: 1.0,
		},
		{
			ID: "rt-air", Name: "Taipei-Frankfurt Air", Origin: "Kaohsiung", Destination: "Rotterdam",
			Mode: "AIR", BaseTransitDays: 4, TransitDelayVarianceDays: 0.5, CongestionSeverity: 10,
			WeatherIncidentCount: 0, Status: RouteClear, CostMultiplier: 2.4, BackupForID: "rt-ocean",
		},
	}

	shipments := []Shipment{
		{
			ID: "shp-101", SupplierID: "sup-primary", RouteID: "rt-ocean", SKU: "SKU-MCU-01",
			Quantity: 1500, Status: ShipmentDelayed,
			ScheduledETA: now.Add(2 * 24 * time.Hour), CurrentETA: now.Add(10 * 24 * time.Hour), DelayHours: 192,
		},
	}

	inventory := []InventoryItem{
		{
			SKU: "SKU-MCU-01", Name: "Automotive MCU", Warehouse: "Munich-WH1",
			PrimarySupplierID: "sup-primary", OnHandUnits: 250, DailyBurnRate: 100, SafetyStockDays: 10,
		},
	}

	// Events intentionally supplied out of order to verify chronological sorting in timeline.
	events := []SupplyEvent{
		{EventID: "ev-2", EventType: EventShipmentDelayed, EntityID: "shp-101", EntityType: "SHIPMENT", OccurredAt: now.Add(-1 * time.Hour), ReceivedAt: now},
		{EventID: "ev-1", EventType: EventSupplierDisruption, EntityID: "sup-primary", EntityType: "SUPPLIER", OccurredAt: now.Add(-3 * time.Hour), ReceivedAt: now.Add(-2 * time.Hour), OutOfOrder: false},
		{EventID: "ev-3", EventType: EventRouteCongestion, EntityID: "rt-ocean", EntityType: "ROUTE", OccurredAt: now.Add(-2 * time.Hour), ReceivedAt: now, OutOfOrder: true},
	}

	incidents := eng.Correlate(suppliers, routes, shipments, inventory, events, now)
	if len(incidents) != 1 {
		t.Fatalf("expected 1 correlated incident, got %d", len(incidents))
	}
	inc := incidents[0]
	if inc.RootSupplierID != "sup-primary" {
		t.Fatalf("expected root supplier sup-primary, got %s", inc.RootSupplierID)
	}
	if len(inc.AffectedRouteIDs) != 1 || inc.AffectedRouteIDs[0] != "rt-ocean" {
		t.Fatalf("expected affected route rt-ocean, got %v", inc.AffectedRouteIDs)
	}
	if len(inc.AffectedShipmentIDs) != 1 || inc.AffectedShipmentIDs[0] != "shp-101" {
		t.Fatalf("expected affected shipment shp-101, got %v", inc.AffectedShipmentIDs)
	}
	if len(inc.ShortageAlerts) != 1 || inc.ShortageAlerts[0].SKU != "SKU-MCU-01" {
		t.Fatalf("expected shortage alert for SKU-MCU-01, got %+v", inc.ShortageAlerts)
	}
	// Timeline must be strictly sorted by OccurredAt: ev-1 (-3h), ev-3 (-2h), ev-2 (-1h).
	if len(inc.Timeline) != 3 || inc.Timeline[0].EventID != "ev-1" || inc.Timeline[1].EventID != "ev-3" || inc.Timeline[2].EventID != "ev-2" {
		t.Fatalf("expected sorted timeline [ev-1, ev-3, ev-2], got %+v", inc.Timeline)
	}
	// Recommendations should include Dresden Fab B ranked above Slow Backup C.
	if len(inc.Recommendations) < 3 {
		t.Fatalf("expected >=3 recommendations (2 suppliers + 1 route), got %d", len(inc.Recommendations))
	}
	var supRank []string
	for _, r := range inc.Recommendations {
		if r.TargetType == "SUPPLIER" {
			supRank = append(supRank, r.AlternativeID)
		}
	}
	if len(supRank) != 2 || supRank[0] != "sup-backup-eu" {
		t.Fatalf("expected sup-backup-eu ranked first among backup suppliers, got %v", supRank)
	}
}
