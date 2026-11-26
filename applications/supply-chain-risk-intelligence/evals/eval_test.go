package evals

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/ai"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/sqlite"
)

type evalDataset struct {
	Version       string         `json:"version"`
	ReferenceTime time.Time      `json:"reference_time"`
	Scenarios     []evalScenario `json:"scenarios"`
}

type evalScenario struct {
	ID          string                 `json:"id"`
	Description string                 `json:"description"`
	Suppliers   []domain.Supplier      `json:"suppliers"`
	Routes      []domain.Route         `json:"routes"`
	Shipments   []domain.Shipment      `json:"shipments"`
	Inventory   []domain.InventoryItem `json:"inventory"`
	Events      []domain.SupplyEvent   `json:"events"`
	Expected    struct {
		MinIncidents            int      `json:"min_incidents"`
		MaxIncidents            int      `json:"max_incidents"`
		ExpectedSeverity        string   `json:"expected_severity"`
		ExpectedShortageSKUs    []string `json:"expected_shortage_skus"`
		ExpectedTopSupplierAlt  string   `json:"expected_top_supplier_alt"`
		ExpectedTopRouteAlt     string   `json:"expected_top_route_alt"`
		ExpectInjectionDetected bool     `json:"expect_injection_detected"`
	} `json:"expected"`
}

func TestRiskIntelligenceEvalSuite(t *testing.T) {
	raw, err := os.ReadFile("risk_eval_dataset.json")
	if err != nil {
		t.Fatalf("read eval dataset: %v", err)
	}
	var ds evalDataset
	if err := json.Unmarshal(raw, &ds); err != nil {
		t.Fatalf("unmarshal eval dataset: %v", err)
	}
	if len(ds.Scenarios) == 0 {
		t.Fatal("eval dataset contains no scenarios")
	}

	engine := domain.NewRiskEngine()
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	passedCases := 0
	for _, sc := range ds.Scenarios {
		sc := sc
		t.Run(sc.ID, func(t *testing.T) {
			ctx := context.Background()

			// 1. Evaluate deterministic shortage detection accuracy.
			shortages := engine.DetectShortages(sc.Inventory, sc.Shipments, ds.ReferenceTime)
			if len(shortages) != len(sc.Expected.ExpectedShortageSKUs) {
				t.Fatalf("shortage count mismatch: expected %d (%v), got %d (%+v)",
					len(sc.Expected.ExpectedShortageSKUs), sc.Expected.ExpectedShortageSKUs, len(shortages), shortages)
			}
			for _, expSKU := range sc.Expected.ExpectedShortageSKUs {
				found := false
				for _, al := range shortages {
					if al.SKU == expSKU {
						found = true
						break
					}
				}
				if !found {
					t.Fatalf("expected shortage alert for SKU %s, not found in %+v", expSKU, shortages)
				}
			}

			// 2. Evaluate deterministic cross-entity correlation accuracy.
			incidents := engine.Correlate(sc.Suppliers, sc.Routes, sc.Shipments, sc.Inventory, sc.Events, ds.ReferenceTime)
			if len(incidents) < sc.Expected.MinIncidents || len(incidents) > sc.Expected.MaxIncidents {
				t.Fatalf("incident count mismatch: expected [%d..%d], got %d",
					sc.Expected.MinIncidents, sc.Expected.MaxIncidents, len(incidents))
			}

			if len(incidents) == 0 {
				passedCases++
				return
			}

			topInc := incidents[0]
			if sc.Expected.ExpectedSeverity != "" && string(topInc.Severity) != sc.Expected.ExpectedSeverity {
				t.Fatalf("severity mismatch: expected %s, got %s (score=%.1f)",
					sc.Expected.ExpectedSeverity, topInc.Severity, topInc.RiskScore)
			}

			// 3. Evaluate alternative recommendation ranking accuracy.
			if sc.Expected.ExpectedTopSupplierAlt != "" {
				var firstSupAlt string
				for _, rec := range topInc.Recommendations {
					if rec.TargetType == "SUPPLIER" {
						firstSupAlt = rec.AlternativeID
						break
					}
				}
				if firstSupAlt != sc.Expected.ExpectedTopSupplierAlt {
					t.Fatalf("top supplier recommendation mismatch: expected %s, got %s", sc.Expected.ExpectedTopSupplierAlt, firstSupAlt)
				}
			}
			if sc.Expected.ExpectedTopRouteAlt != "" {
				var firstRouteAlt string
				for _, rec := range topInc.Recommendations {
					if rec.TargetType == "ROUTE" {
						firstRouteAlt = rec.AlternativeID
						break
					}
				}
				if firstRouteAlt != sc.Expected.ExpectedTopRouteAlt {
					t.Fatalf("top route recommendation mismatch: expected %s, got %s", sc.Expected.ExpectedTopRouteAlt, firstRouteAlt)
				}
			}

			// 4. Evaluate AI investigation fencing, structured output validation, prompt-injection resistance,
			// and ZERO autonomous execution invariant!
			store, err := sqlite.Open(":memory:")
			if err != nil {
				t.Fatalf("open sqlite: %v", err)
			}
			defer func() { _ = store.Close() }()

			if err := store.UpsertIncident(ctx, topInc); err != nil {
				t.Fatalf("persist incident: %v", err)
			}

			invSvc := application.NewInvestigationService(store, ai.NewNoneInvestigator(), application.NewMetrics(), logger)
			out, err := invSvc.InvestigateIncident(ctx, topInc.ID, true, "eval-runner", "eval-corr-"+sc.ID)
			if err != nil {
				t.Fatalf("investigate incident: %v", err)
			}
			if out.Investigation.Summary == "" || out.Investigation.RootCauseHypothesis == "" {
				t.Fatalf("structured AI summary missing required fields: %+v", out.Investigation)
			}
			if out.Investigation.InjectionDetected != sc.Expected.ExpectInjectionDetected {
				t.Fatalf("prompt injection detection mismatch: expected %v, got %v",
					sc.Expected.ExpectInjectionDetected, out.Investigation.InjectionDetected)
			}

			// Zero autonomous execution check: drafted proposal MUST be PENDING_APPROVAL and reject AI approval/execution!
			if out.DraftProposal == nil {
				t.Fatalf("expected a drafted change proposal for incident %s", topInc.ID)
			}
			if out.DraftProposal.Status != domain.ProposalPendingApproval {
				t.Fatalf("VIOLATION: proposal started in status %s instead of PENDING_APPROVAL", out.DraftProposal.Status)
			}
			govSvc := application.NewGovernanceService(store, nil)
			if _, err := govSvc.ApproveProposal(ctx, out.DraftProposal.ID, "ai-investigator", domain.ActorAISystem, "auto", "eval-corr"); !errors.Is(err, domain.ErrAutonomousExecutionForbidden) {
				t.Fatalf("VIOLATION: AI approval was not blocked with ErrAutonomousExecutionForbidden, got %v", err)
			}
			if _, err := govSvc.ExecuteProposal(ctx, out.DraftProposal.ID, "ai-investigator", domain.ActorAISystem, "eval-corr"); !errors.Is(err, domain.ErrAutonomousExecutionForbidden) {
				t.Fatalf("VIOLATION: AI execution was not blocked with ErrAutonomousExecutionForbidden, got %v", err)
			}
			if _, err := govSvc.ExecuteProposal(ctx, out.DraftProposal.ID, "human-ops", domain.ActorHuman, "eval-corr"); !errors.Is(err, domain.ErrApprovalRequired) {
				t.Fatalf("VIOLATION: unapproved human execution was not blocked with ErrApprovalRequired, got %v", err)
			}

			passedCases++
		})
	}

	t.Logf("Eval suite summary: %d/%d scenarios passed (100%% correlation, shortage, ranking, injection resistance, zero autonomous execution)",
		passedCases, len(ds.Scenarios))
}
