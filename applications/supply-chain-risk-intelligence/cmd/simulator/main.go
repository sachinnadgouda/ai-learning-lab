package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/ai"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/broker"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/sqlite"
)

func main() {
	target := flag.String("target", "", "Target server base URL (e.g. http://127.0.0.1:8080). If omitted, runs self-contained local simulation.")
	apiKey := flag.String("api-key", "dev-operator-key", "X-API-Key header for live server mode")
	flag.Parse()

	if *target != "" {
		if err := runAgainstServer(*target, *apiKey); err != nil {
			fmt.Fprintf(os.Stderr, "simulation failed: %v\n", err)
			os.Exit(1)
		}
		return
	}
	if err := runLocalSimulation(); err != nil {
		fmt.Fprintf(os.Stderr, "local simulation failed: %v\n", err)
		os.Exit(1)
	}
}

func runLocalSimulation() error {
	ctx := context.Background()
	now := time.Now().UTC()

	store, err := sqlite.Open(":memory:")
	if err != nil {
		return err
	}
	defer func() { _ = store.Close() }()

	if err := application.SeedRepository(ctx, store, now); err != nil {
		return err
	}

	memBroker := broker.NewMemoryBroker(64)
	metrics := application.NewMetrics()
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	engine := domain.NewRiskEngine()

	proc := application.NewProcessor(
		application.DefaultProcessorConfig(),
		memBroker,
		store,
		engine,
		application.NewInMemoryNotifier(),
		metrics,
		logger,
	)
	workerCtx, cancel := context.WithCancel(ctx)
	proc.Start(workerCtx)

	events := application.BuildDemoCascadeEvents(now)
	fmt.Printf("=== Supply Chain Risk Intelligence Simulator (Local Mode) ===\n")
	fmt.Printf("Seeded 4 suppliers, 4 routes, 4 shipments, 4 SKUs.\n")
	fmt.Printf("Enqueuing %d cascade events (includes 1 duplicate & 1 out-of-order event)...\n", len(events))

	for _, ev := range events {
		if _, err := proc.Enqueue(ctx, ev); err != nil {
			cancel()
			return err
		}
	}

	// Drain worker pool cleanly so every event finishes before summarizing.
	drainCtx, drainCancel := context.WithTimeout(ctx, 5*time.Second)
	defer drainCancel()
	if err := proc.Shutdown(drainCtx); err != nil {
		cancel()
		return err
	}
	cancel()

	snap := metrics.Snapshot()
	fmt.Printf("\n--- Processing Summary ---\n")
	fmt.Printf("Ingested: %d | Processed: %d | Duplicates Skipped: %d | Out-of-Order Recorded: %d | DLQ: %d\n",
		snap["events_ingested_total"],
		snap["events_processed_total"],
		snap["events_duplicate_total"],
		snap["events_out_of_order_total"],
		snap["events_dlq_total"],
	)

	incidents, err := store.ListIncidents(ctx)
	if err != nil {
		return err
	}
	fmt.Printf("\n--- Correlated Incidents (%d) ---\n", len(incidents))
	invSvc := application.NewInvestigationService(store, ai.NewNoneInvestigator(), metrics, logger)
	for _, inc := range incidents {
		fmt.Printf("[%s] %s | Score: %.1f | Routes: %v | Shipments: %v | Shortages: %d | Timeline Events: %d\n",
			inc.Severity, inc.Title, inc.RiskScore, inc.AffectedRouteIDs, inc.AffectedShipmentIDs, len(inc.ShortageAlerts), len(inc.Timeline))
		for _, al := range inc.ShortageAlerts {
			fmt.Printf("  - Shortage %s (%s): %.1f days cover (shortfall %d units, delayed inbound %d)\n",
				al.SKU, al.Severity, al.DaysOfCover, al.ShortfallUnits, al.DelayedInboundUnits)
		}
		if len(inc.Recommendations) > 0 {
			top := inc.Recommendations[0]
			fmt.Printf("  - Top Recommendation [%s]: %s (risk delta %+.1f, lead %+.1fd, cost %+.1f%%)\n",
				top.TargetType, top.AlternativeName, top.RiskScoreDelta, top.LeadTimeDeltaDays, top.CostDeltaPct)
		}
		out, err := invSvc.InvestigateIncident(ctx, inc.ID, true, "simulator-cli", "sim-corr-1")
		if err == nil && out.DraftProposal != nil {
			fmt.Printf("  - Draft Proposal: %s (%s on %s) -> status=%s (human approval required)\n",
				out.DraftProposal.ID, out.DraftProposal.ProposalType, out.DraftProposal.TargetEntityID, out.DraftProposal.Status)
		}
	}
	return nil
}

func runAgainstServer(baseURL, apiKey string) error {
	client := &http.Client{Timeout: 10 * time.Second}
	now := time.Now().UTC()
	ds := application.DefaultSeedDataset(now)

	post := func(path string, body any) error {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		req, err := http.NewRequest(http.MethodPost, baseURL+path, bytes.NewReader(b))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-API-Key", apiKey)
		resp, err := client.Do(req)
		if err != nil {
			return err
		}
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			respBody, _ := io.ReadAll(resp.Body)
			return fmt.Errorf("POST %s returned %d: %s", path, resp.StatusCode, string(respBody))
		}
		return nil
	}

	fmt.Printf("Seeding live server at %s...\n", baseURL)
	for _, s := range ds.Suppliers {
		if err := post("/api/v1/suppliers", s); err != nil {
			return err
		}
	}
	for _, r := range ds.Routes {
		if err := post("/api/v1/routes", r); err != nil {
			return err
		}
	}
	for _, sh := range ds.Shipments {
		if err := post("/api/v1/shipments", sh); err != nil {
			return err
		}
	}
	for _, it := range ds.Inventory {
		if err := post("/api/v1/inventory", it); err != nil {
			return err
		}
	}

	events := application.BuildDemoCascadeEvents(now)
	fmt.Printf("Submitting %d cascade events...\n", len(events))
	if err := post("/api/v1/events", map[string]any{"events": events}); err != nil {
		return err
	}
	fmt.Printf("Simulation events accepted by %s. Inspect GET /api/v1/incidents and GET /metrics.\n", baseURL)
	return nil
}
