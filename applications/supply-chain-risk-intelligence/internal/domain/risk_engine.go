package domain

import (
	"fmt"
	"math"
	"sort"
	"strings"
	"time"
)

// RiskEngine implements 100% deterministic supply-chain risk scoring,
// inventory shortage projection, cross-entity correlation, and alternative ranking.
type RiskEngine struct{}

func NewRiskEngine() *RiskEngine {
	return &RiskEngine{}
}

func clamp(v, minV, maxV float64) float64 {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return minV
	}
	if v < minV {
		return minV
	}
	if v > maxV {
		return maxV
	}
	return v
}

func round2(v float64) float64 {
	return math.Round(v*100) / 100
}

// ScoreSupplier computes a deterministic 0-100 risk score with explicit contributing factors.
func (e *RiskEngine) ScoreSupplier(s Supplier, now time.Time) RiskScore {
	factors := make([]RiskFactor, 0, 5)
	total := 0.0

	// 1. Historical delay ratio (up to 30 pts)
	delayRatio := clamp(s.HistoricalDelayRatio, 0, 1)
	delayPts := round2(delayRatio * 30.0)
	total += delayPts
	factors = append(factors, RiskFactor{
		Name:         "historical_delay_ratio",
		Contribution: delayPts,
		Detail:       fmt.Sprintf("%.1f%% historical late deliveries", delayRatio*100),
	})

	// 2. Active disruption severity (up to 40 pts)
	disruption := clamp(s.ActiveDisruptionSeverity, 0, 100)
	disruptionPts := round2(disruption * 0.40)
	if s.Status == SupplierDisrupted && disruptionPts < 28 {
		disruptionPts = 28
	} else if s.Status == SupplierDegraded && disruptionPts < 14 {
		disruptionPts = 14
	}
	total += disruptionPts
	factors = append(factors, RiskFactor{
		Name:         "active_disruption_severity",
		Contribution: disruptionPts,
		Detail:       fmt.Sprintf("status=%s disruption_severity=%.1f", defaultSupplierStatus(s.Status), disruption),
	})

	// 3. Single-source criticality (15 pts)
	singlePts := 0.0
	if s.SingleSource {
		singlePts = 15.0
	}
	total += singlePts
	factors = append(factors, RiskFactor{
		Name:         "single_source_criticality",
		Contribution: singlePts,
		Detail:       fmt.Sprintf("single_source=%t", s.SingleSource),
	})

	// 4. Tier rating (Tier 1 = 15, Tier 2 = 9, Tier 3+ = 4; missing/invalid defaults to Tier 2)
	tierPts := 9.0
	tierDetail := fmt.Sprintf("tier=%d", s.Tier)
	switch {
	case s.Tier == 1:
		tierPts = 15.0
	case s.Tier == 2:
		tierPts = 9.0
	case s.Tier >= 3:
		tierPts = 4.0
	default:
		tierPts = 9.0
		tierDetail = "tier missing/invalid; defaulted to Tier 2 risk weight"
	}
	total += tierPts
	factors = append(factors, RiskFactor{
		Name:         "tier_criticality",
		Contribution: tierPts,
		Detail:       tierDetail,
	})

	score := round2(clamp(total, 0, 100))
	return RiskScore{
		EntityID:   s.ID,
		EntityType: "SUPPLIER",
		EntityName: s.Name,
		Score:      score,
		Severity:   SeverityFromScore(score),
		Factors:    factors,
		ComputedAt: now.UTC(),
	}
}

func defaultSupplierStatus(s SupplierStatus) SupplierStatus {
	if s == "" {
		return SupplierOperational
	}
	return s
}

// ScoreRoute computes a deterministic 0-100 route risk score.
func (e *RiskEngine) ScoreRoute(r Route, activeDelayedShipments int, now time.Time) RiskScore {
	factors := make([]RiskFactor, 0, 4)
	total := 0.0

	// 1. Transit delay variance (up to 30 pts; 10+ days variance saturates)
	variance := clamp(r.TransitDelayVarianceDays, 0, 30)
	varPts := round2(math.Min(variance/10.0, 1.0) * 30.0)
	total += varPts
	factors = append(factors, RiskFactor{
		Name:         "transit_delay_variance",
		Contribution: varPts,
		Detail:       fmt.Sprintf("%.1f days transit variance (base %.1f days)", variance, math.Max(0, r.BaseTransitDays)),
	})

	// 2. Congestion severity (up to 35 pts)
	congestion := clamp(r.CongestionSeverity, 0, 100)
	congPts := round2(congestion * 0.35)
	if r.Status == RouteBlocked && congPts < 35 {
		congPts = 35
	} else if r.Status == RouteCongested && congPts < 18 {
		congPts = 18
	}
	total += congPts
	factors = append(factors, RiskFactor{
		Name:         "route_congestion",
		Contribution: congPts,
		Detail:       fmt.Sprintf("status=%s congestion=%.1f", defaultRouteStatus(r.Status), congestion),
	})

	// 3. Weather / disruption incidents (up to 15 pts)
	weatherCount := r.WeatherIncidentCount
	if weatherCount < 0 {
		weatherCount = 0
	}
	weatherPts := round2(math.Min(float64(weatherCount), 5.0) * 3.0)
	total += weatherPts
	factors = append(factors, RiskFactor{
		Name:         "weather_incidents",
		Contribution: weatherPts,
		Detail:       fmt.Sprintf("%d active weather/port incidents", weatherCount),
	})

	// 4. Active delayed shipment count (up to 20 pts)
	if activeDelayedShipments < 0 {
		activeDelayedShipments = 0
	}
	delayedPts := round2(math.Min(float64(activeDelayedShipments), 5.0) * 4.0)
	total += delayedPts
	factors = append(factors, RiskFactor{
		Name:         "active_delayed_shipments",
		Contribution: delayedPts,
		Detail:       fmt.Sprintf("%d active delayed shipments on route", activeDelayedShipments),
	})

	if r.Status == RouteBlocked && total < 80 {
		total = 80
	}

	score := round2(clamp(total, 0, 100))
	return RiskScore{
		EntityID:   r.ID,
		EntityType: "ROUTE",
		EntityName: r.Name,
		Score:      score,
		Severity:   SeverityFromScore(score),
		Factors:    factors,
		ComputedAt: now.UTC(),
	}
}

func defaultRouteStatus(s RouteStatus) RouteStatus {
	if s == "" {
		return RouteClear
	}
	return s
}

// DetectShortages projects inventory days-of-cover against safety-stock thresholds and delayed inbound ETAs.
func (e *RiskEngine) DetectShortages(items []InventoryItem, shipments []Shipment, now time.Time) []ShortageAlert {
	nowUTC := now.UTC()
	shipmentsBySKU := make(map[string][]Shipment)
	for _, sh := range shipments {
		if sh.Status == ShipmentDelivered {
			continue
		}
		shipmentsBySKU[sh.SKU] = append(shipmentsBySKU[sh.SKU], sh)
	}

	alerts := make([]ShortageAlert, 0)
	for _, item := range items {
		onHand := item.OnHandUnits
		if onHand < 0 {
			onHand = 0
		}
		burn := item.DailyBurnRate
		safetyDays := item.SafetyStockDays
		if safetyDays <= 0 {
			safetyDays = 7
		}

		if burn <= 0 {
			// Guard against zero/negative burn rate: only alert if inventory is already depleted.
			if onHand == 0 {
				stockout := nowUTC
				alerts = append(alerts, ShortageAlert{
					SKU:                   item.SKU,
					Name:                  item.Name,
					Warehouse:             item.Warehouse,
					PrimarySupplierID:     item.PrimarySupplierID,
					OnHandUnits:           0,
					DailyBurnRate:         0,
					DaysOfCover:           0,
					SafetyStockDays:       safetyDays,
					ProjectedStockoutDate: &stockout,
					ShortfallUnits:        maxInt(item.ReorderPointUnits, 1),
					Severity:              SeverityHigh,
					Explanation:           "Zero on-hand inventory with zero/missing daily burn rate; requires replenishment verification.",
					ComputedAt:            nowUTC,
				})
			}
			continue
		}

		daysOfCover := round2(float64(onHand) / burn)
		stockoutDate := nowUTC.Add(time.Duration(daysOfCover * float64(24*time.Hour)))

		var earliestETA *time.Time
		delayedUnits := 0
		hasDelayedInbound := false
		for _, sh := range shipmentsBySKU[item.SKU] {
			eta := sh.CurrentETA
			if eta.IsZero() {
				eta = sh.ScheduledETA
			}
			if !eta.IsZero() && (earliestETA == nil || eta.Before(*earliestETA)) {
				etaCopy := eta.UTC()
				earliestETA = &etaCopy
			}
			if sh.Status == ShipmentDelayed || sh.DelayHours > 0 || (!sh.ScheduledETA.IsZero() && sh.CurrentETA.After(sh.ScheduledETA)) {
				hasDelayedInbound = true
				delayedUnits += maxInt(sh.Quantity, 0)
			}
		}

		inboundMissesStockout := earliestETA != nil && earliestETA.After(stockoutDate)
		belowSafety := daysOfCover < safetyDays
		belowReorderWithDelay := item.ReorderPointUnits > 0 && onHand <= item.ReorderPointUnits && hasDelayedInbound

		if !belowSafety && !inboundMissesStockout && !belowReorderWithDelay {
			continue
		}

		// Compute shortfall units: cover safety stock gap and any gap before earliest inbound ETA.
		shortfallDays := math.Max(0, safetyDays-daysOfCover)
		if inboundMissesStockout {
			gapDays := earliestETA.Sub(stockoutDate).Hours() / 24.0
			if gapDays > shortfallDays {
				shortfallDays = gapDays
			}
		}
		shortfallUnits := int(math.Ceil(shortfallDays * burn))
		if shortfallUnits == 0 && belowReorderWithDelay {
			shortfallUnits = maxInt(item.ReorderPointUnits-onHand, int(math.Ceil(burn*2)))
		}

		severity := SeverityMedium
		switch {
		case daysOfCover <= 3 || (inboundMissesStockout && daysOfCover <= safetyDays*0.6):
			severity = SeverityCritical
		case daysOfCover < safetyDays*0.5 || inboundMissesStockout:
			severity = SeverityHigh
		default:
			severity = SeverityMedium
		}

		var reasons []string
		if belowSafety {
			reasons = append(reasons, fmt.Sprintf("days_of_cover=%.1f below safety_stock=%.1f days", daysOfCover, safetyDays))
		}
		if hasDelayedInbound {
			reasons = append(reasons, fmt.Sprintf("%d inbound units delayed", delayedUnits))
		}
		if inboundMissesStockout {
			reasons = append(reasons, fmt.Sprintf("earliest inbound ETA (%s) misses projected stockout (%s)", earliestETA.Format("2006-01-02"), stockoutDate.Format("2006-01-02")))
		}

		alerts = append(alerts, ShortageAlert{
			SKU:                   item.SKU,
			Name:                  item.Name,
			Warehouse:             item.Warehouse,
			PrimarySupplierID:     item.PrimarySupplierID,
			OnHandUnits:           onHand,
			DailyBurnRate:         burn,
			DaysOfCover:           daysOfCover,
			SafetyStockDays:       safetyDays,
			DelayedInboundUnits:   delayedUnits,
			EarliestInboundETA:    earliestETA,
			ProjectedStockoutDate: &stockoutDate,
			ShortfallUnits:        shortfallUnits,
			Severity:              severity,
			Explanation:           strings.Join(reasons, "; "),
			ComputedAt:            nowUTC,
		})
	}

	sort.Slice(alerts, func(i, j int) bool {
		if SeverityRank(alerts[i].Severity) != SeverityRank(alerts[j].Severity) {
			return SeverityRank(alerts[i].Severity) > SeverityRank(alerts[j].Severity)
		}
		if alerts[i].DaysOfCover != alerts[j].DaysOfCover {
			return alerts[i].DaysOfCover < alerts[j].DaysOfCover
		}
		return alerts[i].SKU < alerts[j].SKU
	})

	return alerts
}

// RecommendAlternatives ranks deterministic backup suppliers and routes.
func (e *RiskEngine) RecommendAlternatives(
	disruptedSupplier *Supplier,
	affectedRoutes []Route,
	allSuppliers []Supplier,
	allRoutes []Route,
	supplierScores map[string]RiskScore,
	routeScores map[string]RiskScore,
) []AlternativeRecommendation {
	recs := make([]AlternativeRecommendation, 0)

	if disruptedSupplier != nil {
		curScore := supplierScores[disruptedSupplier.ID].Score
		for _, cand := range allSuppliers {
			if cand.ID == disruptedSupplier.ID || cand.Status == SupplierDisrupted {
				continue
			}
			isBackup := cand.BackupForID == disruptedSupplier.ID ||
				(disruptedSupplier.Category != "" && strings.EqualFold(cand.Category, disruptedSupplier.Category))
			if !isBackup {
				continue
			}
			candScore := supplierScores[cand.ID].Score
			riskDelta := round2(candScore - curScore) // negative is safer
			leadDelta := round2(cand.LeadTimeDays - disruptedSupplier.LeadTimeDays)
			costDeltaPct := 0.0
			if disruptedSupplier.UnitCostUSD > 0 {
				costDeltaPct = round2(((cand.UnitCostUSD - disruptedSupplier.UnitCostUSD) / disruptedSupplier.UnitCostUSD) * 100.0)
			}
			// Higher RankScore is better: reward risk reduction, penalize extra lead time and cost.
			rankScore := round2((-riskDelta)*1.2 - leadDelta*2.5 - math.Max(0, costDeltaPct)*0.5)
			recs = append(recs, AlternativeRecommendation{
				TargetType:        "SUPPLIER",
				CurrentID:         disruptedSupplier.ID,
				AlternativeID:     cand.ID,
				AlternativeName:   cand.Name,
				LeadTimeDeltaDays: leadDelta,
				CostDeltaPct:      costDeltaPct,
				RiskScoreDelta:    riskDelta,
				Rationale:         fmt.Sprintf("Backup supplier in %s reduces supplier risk by %.1f pts (lead time %+.1fd, cost %+.1f%%)", cand.Region, -riskDelta, leadDelta, costDeltaPct),
				RankScore:         rankScore,
			})
		}
	}

	seenRoutePairs := make(map[string]struct{})
	for _, r := range affectedRoutes {
		curScore := routeScores[r.ID].Score
		for _, cand := range allRoutes {
			if cand.ID == r.ID || cand.Status == RouteBlocked {
				continue
			}
			isBackup := cand.BackupForID == r.ID ||
				(strings.EqualFold(cand.Origin, r.Origin) && strings.EqualFold(cand.Destination, r.Destination))
			if !isBackup {
				continue
			}
			pairKey := r.ID + "->" + cand.ID
			if _, ok := seenRoutePairs[pairKey]; ok {
				continue
			}
			seenRoutePairs[pairKey] = struct{}{}

			candScore := routeScores[cand.ID].Score
			riskDelta := round2(candScore - curScore)
			curEffectiveLead := r.BaseTransitDays + r.TransitDelayVarianceDays
			candEffectiveLead := cand.BaseTransitDays + cand.TransitDelayVarianceDays
			leadDelta := round2(candEffectiveLead - curEffectiveLead)
			costDeltaPct := 0.0
			if r.CostMultiplier > 0 {
				costDeltaPct = round2(((cand.CostMultiplier - r.CostMultiplier) / r.CostMultiplier) * 100.0)
			}
			rankScore := round2((-riskDelta)*1.2 - leadDelta*2.5 - math.Max(0, costDeltaPct)*0.4)
			recs = append(recs, AlternativeRecommendation{
				TargetType:        "ROUTE",
				CurrentID:         r.ID,
				AlternativeID:     cand.ID,
				AlternativeName:   cand.Name,
				LeadTimeDeltaDays: leadDelta,
				CostDeltaPct:      costDeltaPct,
				RiskScoreDelta:    riskDelta,
				Rationale:         fmt.Sprintf("Alternative %s route (%s -> %s) changes risk by %+.1f pts (effective transit %+.1fd, cost %+.1f%%)", cand.Mode, cand.Origin, cand.Destination, riskDelta, leadDelta, costDeltaPct),
				RankScore:         rankScore,
			})
		}
	}

	sort.Slice(recs, func(i, j int) bool {
		if recs[i].RankScore != recs[j].RankScore {
			return recs[i].RankScore > recs[j].RankScore
		}
		if recs[i].TargetType != recs[j].TargetType {
			return recs[i].TargetType < recs[j].TargetType
		}
		return recs[i].AlternativeID < recs[j].AlternativeID
	})

	return recs
}

// Correlate builds cross-entity correlated incidents linking disrupted suppliers,
// congested routes, delayed shipments, and impending inventory shortages with an ordered event timeline.
func (e *RiskEngine) Correlate(
	suppliers []Supplier,
	routes []Route,
	shipments []Shipment,
	inventory []InventoryItem,
	events []SupplyEvent,
	now time.Time,
) []CorrelatedIncident {
	nowUTC := now.UTC()

	delayedByRoute := make(map[string]int)
	for _, sh := range shipments {
		if sh.Status == ShipmentDelayed || sh.DelayHours > 0 {
			delayedByRoute[sh.RouteID]++
		}
	}

	supplierByID := make(map[string]Supplier, len(suppliers))
	supplierScores := make(map[string]RiskScore, len(suppliers))
	for _, s := range suppliers {
		supplierByID[s.ID] = s
		supplierScores[s.ID] = e.ScoreSupplier(s, nowUTC)
	}

	routeByID := make(map[string]Route, len(routes))
	routeScores := make(map[string]RiskScore, len(routes))
	for _, r := range routes {
		routeByID[r.ID] = r
		routeScores[r.ID] = e.ScoreRoute(r, delayedByRoute[r.ID], nowUTC)
	}

	shortages := e.DetectShortages(inventory, shipments, nowUTC)
	shortageBySKU := make(map[string]ShortageAlert, len(shortages))
	for _, al := range shortages {
		shortageBySKU[al.SKU] = al
	}

	// Build incidents anchored on disrupted/high-risk suppliers first, then orphan high-risk routes.
	incidents := make([]CorrelatedIncident, 0)
	coveredRoutes := make(map[string]bool)
	coveredShipments := make(map[string]bool)

	sortedSuppliers := append([]Supplier(nil), suppliers...)
	sort.Slice(sortedSuppliers, func(i, j int) bool {
		if supplierScores[sortedSuppliers[i].ID].Score != supplierScores[sortedSuppliers[j].ID].Score {
			return supplierScores[sortedSuppliers[i].ID].Score > supplierScores[sortedSuppliers[j].ID].Score
		}
		return sortedSuppliers[i].ID < sortedSuppliers[j].ID
	})

	for _, sup := range sortedSuppliers {
		supScore := supplierScores[sup.ID]
		// Check if supplier has high risk, disruption, or delayed shipments causing shortages
		supShipments := make([]Shipment, 0)
		hasDelayed := false
		for _, sh := range shipments {
			if sh.SupplierID == sup.ID {
				supShipments = append(supShipments, sh)
				if sh.Status == ShipmentDelayed || sh.DelayHours >= 12 {
					hasDelayed = true
				}
			}
		}

		supShortages := make([]ShortageAlert, 0)
		skuSet := make(map[string]struct{})
		for _, item := range inventory {
			if item.PrimarySupplierID == sup.ID {
				if al, ok := shortageBySKU[item.SKU]; ok {
					supShortages = append(supShortages, al)
					skuSet[item.SKU] = struct{}{}
				}
			}
		}

		isTriggered := supScore.Score >= 55 || sup.Status == SupplierDisrupted || (hasDelayed && len(supShortages) > 0)
		if !isTriggered {
			continue
		}

		routeSet := make(map[string]struct{})
		shipmentIDs := make([]string, 0)
		for _, sh := range supShipments {
			if sh.Status == ShipmentDelayed || sh.DelayHours > 0 || sup.Status == SupplierDisrupted || supScore.Score >= 55 {
				shipmentIDs = append(shipmentIDs, sh.ID)
				coveredShipments[sh.ID] = true
				if sh.RouteID != "" {
					routeSet[sh.RouteID] = struct{}{}
					coveredRoutes[sh.RouteID] = true
				}
				if sh.SKU != "" {
					if al, ok := shortageBySKU[sh.SKU]; ok {
						if _, exists := skuSet[sh.SKU]; !exists {
							supShortages = append(supShortages, al)
							skuSet[sh.SKU] = struct{}{}
						}
					} else {
						skuSet[sh.SKU] = struct{}{}
					}
				}
			}
		}

		routeIDs := sortedKeys(routeSet)
		sort.Strings(shipmentIDs)
		affectedSKUs := sortedKeys(skuSet)

		affectedRoutes := make([]Route, 0, len(routeIDs))
		maxRouteScore := 0.0
		for _, rid := range routeIDs {
			if rt, ok := routeByID[rid]; ok {
				affectedRoutes = append(affectedRoutes, rt)
			}
			if rs := routeScores[rid].Score; rs > maxRouteScore {
				maxRouteScore = rs
			}
		}

		// Compute correlated incident score combining supplier, route, and shortage severity.
		shortageBoost := 0.0
		for _, al := range supShortages {
			switch al.Severity {
			case SeverityCritical:
				shortageBoost = math.Max(shortageBoost, 20)
			case SeverityHigh:
				shortageBoost = math.Max(shortageBoost, 12)
			case SeverityMedium:
				shortageBoost = math.Max(shortageBoost, 6)
			}
		}
		incidentScore := round2(clamp(math.Max(supScore.Score, maxRouteScore)*0.75+shortageBoost+math.Min(float64(len(shipmentIDs))*3, 15), 0, 100))

		supCopy := sup
		recs := e.RecommendAlternatives(&supCopy, affectedRoutes, suppliers, routes, supplierScores, routeScores)
		timeline := buildTimeline(events, sup.ID, routeSet, toSet(shipmentIDs), skuSet)

		summary := fmt.Sprintf(
			"Supplier %s (%s, risk %.1f) linked to %d route(s), %d shipment(s), and %d projected SKU shortage(s).",
			sup.Name, sup.ID, supScore.Score, len(routeIDs), len(shipmentIDs), len(supShortages),
		)

		incidents = append(incidents, CorrelatedIncident{
			ID:                  "inc-" + strings.ToLower(sup.ID),
			Title:               fmt.Sprintf("Supply Chain Cascade: %s (%s)", sup.Name, sup.Region),
			Status:              IncidentOpen,
			Severity:            SeverityFromScore(incidentScore),
			RootSupplierID:      sup.ID,
			AffectedRouteIDs:    routeIDs,
			AffectedShipmentIDs: shipmentIDs,
			AffectedSKUs:        affectedSKUs,
			RiskScore:           incidentScore,
			Summary:             summary,
			Timeline:            timeline,
			ShortageAlerts:      supShortages,
			Recommendations:     recs,
			CreatedAt:           nowUTC,
			UpdatedAt:           nowUTC,
		})
	}

	// Second pass: congested/high-risk routes not already covered by a supplier cascade.
	sortedRoutes := append([]Route(nil), routes...)
	sort.Slice(sortedRoutes, func(i, j int) bool {
		if routeScores[sortedRoutes[i].ID].Score != routeScores[sortedRoutes[j].ID].Score {
			return routeScores[sortedRoutes[i].ID].Score > routeScores[sortedRoutes[j].ID].Score
		}
		return sortedRoutes[i].ID < sortedRoutes[j].ID
	})

	for _, rt := range sortedRoutes {
		if coveredRoutes[rt.ID] {
			continue
		}
		rs := routeScores[rt.ID]
		if rs.Score < 55 && rt.Status == RouteClear {
			continue
		}

		shipmentIDs := make([]string, 0)
		skuSet := make(map[string]struct{})
		rtShortages := make([]ShortageAlert, 0)
		for _, sh := range shipments {
			if sh.RouteID == rt.ID && !coveredShipments[sh.ID] {
				shipmentIDs = append(shipmentIDs, sh.ID)
				if sh.SKU != "" {
					skuSet[sh.SKU] = struct{}{}
					if al, ok := shortageBySKU[sh.SKU]; ok {
						rtShortages = append(rtShortages, al)
					}
				}
			}
		}
		sort.Strings(shipmentIDs)
		routeSet := map[string]struct{}{rt.ID: {}}
		recs := e.RecommendAlternatives(nil, []Route{rt}, suppliers, routes, supplierScores, routeScores)
		timeline := buildTimeline(events, "", routeSet, toSet(shipmentIDs), skuSet)

		shortageBoost := 0.0
		for _, al := range rtShortages {
			if al.Severity == SeverityCritical {
				shortageBoost = math.Max(shortageBoost, 18)
			} else if al.Severity == SeverityHigh {
				shortageBoost = math.Max(shortageBoost, 10)
			}
		}
		incidentScore := round2(clamp(rs.Score*0.85+shortageBoost, 0, 100))

		incidents = append(incidents, CorrelatedIncident{
			ID:                  "inc-route-" + strings.ToLower(rt.ID),
			Title:               fmt.Sprintf("Route Disruption: %s (%s -> %s)", rt.Name, rt.Origin, rt.Destination),
			Status:              IncidentOpen,
			Severity:            SeverityFromScore(incidentScore),
			AffectedRouteIDs:    []string{rt.ID},
			AffectedShipmentIDs: shipmentIDs,
			AffectedSKUs:        sortedKeys(skuSet),
			RiskScore:           incidentScore,
			Summary:             fmt.Sprintf("Route %s (%s, risk %.1f) impacting %d shipment(s) and %d SKU shortage(s).", rt.Name, rt.ID, rs.Score, len(shipmentIDs), len(rtShortages)),
			Timeline:            timeline,
			ShortageAlerts:      rtShortages,
			Recommendations:     recs,
			CreatedAt:           nowUTC,
			UpdatedAt:           nowUTC,
		})
	}

	sort.Slice(incidents, func(i, j int) bool {
		if incidents[i].RiskScore != incidents[j].RiskScore {
			return incidents[i].RiskScore > incidents[j].RiskScore
		}
		return incidents[i].ID < incidents[j].ID
	})

	return incidents
}

func buildTimeline(events []SupplyEvent, supplierID string, routes, shipments, skus map[string]struct{}) []TimelineEvent {
	tl := make([]TimelineEvent, 0)
	for _, ev := range events {
		matched := false
		if supplierID != "" && ev.EntityID == supplierID {
			matched = true
		}
		if _, ok := routes[ev.EntityID]; ok {
			matched = true
		}
		if _, ok := shipments[ev.EntityID]; ok {
			matched = true
		}
		if _, ok := skus[ev.EntityID]; ok {
			matched = true
		}
		if !matched {
			continue
		}
		tl = append(tl, TimelineEvent{
			EventID:    ev.EventID,
			EventType:  ev.EventType,
			EntityID:   ev.EntityID,
			EntityType: ev.EntityType,
			OccurredAt: ev.OccurredAt.UTC(),
			ReceivedAt: ev.ReceivedAt.UTC(),
			OutOfOrder: ev.OutOfOrder,
			Summary:    fmt.Sprintf("%s on %s", ev.EventType, ev.EntityID),
			Notes:      ev.Notes,
		})
	}
	// Ordered strictly by OccurredAt ascending, deterministic tie-breaker on EventID.
	sort.Slice(tl, func(i, j int) bool {
		if !tl[i].OccurredAt.Equal(tl[j].OccurredAt) {
			return tl[i].OccurredAt.Before(tl[j].OccurredAt)
		}
		return tl[i].EventID < tl[j].EventID
	})
	return tl
}

func sortedKeys(m map[string]struct{}) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func toSet(items []string) map[string]struct{} {
	s := make(map[string]struct{}, len(items))
	for _, it := range items {
		s[it] = struct{}{}
	}
	return s
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
