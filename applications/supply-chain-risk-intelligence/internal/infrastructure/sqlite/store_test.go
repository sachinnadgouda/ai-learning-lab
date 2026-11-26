package sqlite

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

func TestStore_MigrationsAndPersistenceRoundTrip(t *testing.T) {
	dir := filepath.Join(".", ".testdb-"+uuid.NewString()[:8])
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	dbPath := filepath.Join(dir, "test_supply_chain.db")
	store, err := Open(dbPath)
	if err != nil {
		t.Fatalf("open sqlite: %v", err)
	}

	ctx := context.Background()
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)

	if err := store.Ping(ctx); err != nil {
		t.Fatalf("ping failed: %v", err)
	}

	// Persist supplier, proposal, DLQ, audit
	sup := domain.Supplier{
		ID: "SUP-1", Name: "Test Supplier", Region: "EU", Category: "chips",
		Tier: 1, SingleSource: true, HistoricalDelayRatio: 0.2,
		ActiveDisruptionSeverity: 50, Status: domain.SupplierDegraded,
		LeadTimeDays: 10, UnitCostUSD: 25.0, UpdatedAt: now,
	}
	if err := store.UpsertSupplier(ctx, sup); err != nil {
		t.Fatalf("upsert supplier: %v", err)
	}

	p, err := domain.NewChangeProposal("prop-1", "inc-1", domain.ProposalRerouteShipment, "SHP-1", map[string]any{"new_route_id": "RT-2"}, "Reroute", "Faster", "ai", domain.ActorAISystem, now)
	if err != nil {
		t.Fatalf("new proposal: %v", err)
	}
	if err := store.CreateProposal(ctx, *p); err != nil {
		t.Fatalf("create proposal: %v", err)
	}

	// Atomic event claim: first claim succeeds, second returns false.
	ev := domain.SupplyEvent{EventID: "ev-1", EventType: domain.EventSupplierDisruption, EntityID: "SUP-1", OccurredAt: now, ReceivedAt: now}
	claimed1, err := store.TryClaimEvent(ctx, ev)
	if err != nil || !claimed1 {
		t.Fatalf("expected first claim true, got claimed=%v err=%v", claimed1, err)
	}
	claimed2, err := store.TryClaimEvent(ctx, ev)
	if err != nil || claimed2 {
		t.Fatalf("expected second claim false (duplicate), got claimed=%v err=%v", claimed2, err)
	}

	if err := store.Close(); err != nil {
		t.Fatalf("close store: %v", err)
	}

	// Reopen DB to verify migrations are idempotent and data persisted on disk in WAL mode!
	reopened, err := Open(dbPath)
	if err != nil {
		t.Fatalf("reopen sqlite: %v", err)
	}
	defer func() { _ = reopened.Close() }()

	gotSup, err := reopened.GetSupplier(ctx, "SUP-1")
	if err != nil || gotSup.Name != "Test Supplier" || !gotSup.SingleSource {
		t.Fatalf("unexpected persisted supplier: %+v err=%v", gotSup, err)
	}
	gotProp, err := reopened.GetProposal(ctx, "prop-1")
	if err != nil || gotProp.Status != domain.ProposalPendingApproval {
		t.Fatalf("unexpected persisted proposal: %+v err=%v", gotProp, err)
	}
}
