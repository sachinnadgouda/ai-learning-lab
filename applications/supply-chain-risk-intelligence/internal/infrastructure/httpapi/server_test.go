package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/ai"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/broker"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/httpapi"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/sqlite"
)

type failingInvestigator struct{}

func (f *failingInvestigator) ProviderName() string { return "failing-mock" }
func (f *failingInvestigator) Investigate(_ context.Context, _ application.InvestigationRequest) (*domain.AIInvestigationResult, error) {
	return nil, domain.ErrUpstreamUnavailable
}

func setupTestServer(t *testing.T, investigator application.AIInvestigator) (*httptest.Server, *application.Processor, *application.Metrics) {
	t.Helper()
	store, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatalf("open sqlite: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })

	memBroker := broker.NewMemoryBroker(64)
	metrics := application.NewMetrics()
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	engine := domain.NewRiskEngine()

	proc := application.NewProcessor(
		application.ProcessorConfig{
			WorkerCount:    2,
			MaxRetries:     1,
			BaseBackoff:    10 * time.Millisecond,
			MaxBackoff:     40 * time.Millisecond,
			ProcessTimeout: 3 * time.Second,
		},
		memBroker,
		store,
		engine,
		application.NewInMemoryNotifier(),
		metrics,
		logger,
	)
	wCtx, cancel := context.WithCancel(context.Background())
	proc.Start(wCtx)
	t.Cleanup(func() {
		_ = proc.Shutdown(context.Background())
		cancel()
	})

	if investigator == nil {
		investigator = ai.NewNoneInvestigator()
	}
	invSvc := application.NewInvestigationService(store, investigator, metrics, logger)
	govSvc := application.NewGovernanceService(store, proc)

	auth := httpapi.NewKeyAuthenticator("op-key", "app-key", "adm-key").
		WithAIServiceKey("ai-key").
		WithAuditSink(store)
	handler := httpapi.NewHandler(httpapi.ServerDeps{
		Repo:          store,
		Broker:        memBroker,
		Processor:     proc,
		Investigator:  invSvc,
		Governance:    govSvc,
		Engine:        engine,
		Metrics:       metrics,
		Auth:          auth,
		RateLimiter:   httpapi.NewIPRateLimiter(1000, 1000),
		AllowedOrigin: []string{"http://localhost:3000"},
		Logger:        logger,
	})

	ts := httptest.NewServer(handler)
	t.Cleanup(ts.Close)
	return ts, proc, metrics
}

func doJSON(t *testing.T, method, url, apiKey string, extraHeaders map[string]string, body any) (*http.Response, []byte) {
	t.Helper()
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("marshal body: %v", err)
		}
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, url, reader)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if apiKey != "" {
		req.Header.Set("X-API-Key", apiKey)
	}
	for k, v := range extraHeaders {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("http %s %s failed: %v", method, url, err)
	}
	raw, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	return resp, raw
}

func TestHTTPAPI_EndToEndWorkflow_RBAC_And_Governance(t *testing.T) {
	ts, _, metrics := setupTestServer(t, ai.NewNoneInvestigator())
	now := time.Now().UTC()

	// 1. Operational endpoints: /health, /ready, /metrics
	resp, _ := doJSON(t, http.MethodGet, ts.URL+"/health", "", nil, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 /health, got %d", resp.StatusCode)
	}
	resp, _ = doJSON(t, http.MethodGet, ts.URL+"/ready", "", nil, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 /ready, got %d", resp.StatusCode)
	}

	// 2. Unauthenticated call -> 401 RFC 9457 Problem Details
	resp, _ = doJSON(t, http.MethodGet, ts.URL+"/api/v1/incidents", "", nil, nil)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 without API key, got %d", resp.StatusCode)
	}
	if !strings.Contains(resp.Header.Get("Content-Type"), "application/problem+json") {
		t.Fatalf("expected RFC 9457 content-type, got %s", resp.Header.Get("Content-Type"))
	}

	// 3. Seed entities via REST API
	ds := application.DefaultSeedDataset(now)
	for _, s := range ds.Suppliers {
		resp, raw := doJSON(t, http.MethodPost, ts.URL+"/api/v1/suppliers", "op-key", nil, s)
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("seed supplier %s: %d %s", s.ID, resp.StatusCode, string(raw))
		}
	}
	for _, r := range ds.Routes {
		resp, raw := doJSON(t, http.MethodPost, ts.URL+"/api/v1/routes", "op-key", nil, r)
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("seed route %s: %d %s", r.ID, resp.StatusCode, string(raw))
		}
	}
	for _, sh := range ds.Shipments {
		resp, raw := doJSON(t, http.MethodPost, ts.URL+"/api/v1/shipments", "op-key", nil, sh)
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("seed shipment %s: %d %s", sh.ID, resp.StatusCode, string(raw))
		}
	}
	for _, it := range ds.Inventory {
		resp, raw := doJSON(t, http.MethodPost, ts.URL+"/api/v1/inventory", "op-key", nil, it)
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("seed inventory %s: %d %s", it.SKU, resp.StatusCode, string(raw))
		}
	}

	// 4. Ingest cascade batch via POST /api/v1/events -> 202 Accepted
	events := application.BuildDemoCascadeEvents(now)
	resp, raw := doJSON(t, http.MethodPost, ts.URL+"/api/v1/events", "op-key", map[string]string{"X-Correlation-ID": "corr-e2e-1"}, map[string]any{"events": events})
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted on event ingest, got %d: %s", resp.StatusCode, string(raw))
	}
	if resp.Header.Get("X-Correlation-ID") != "corr-e2e-1" {
		t.Fatalf("expected X-Correlation-ID echoed, got %q", resp.Header.Get("X-Correlation-ID"))
	}

	// Wait for async worker pool to process all 7 events (6 unique + 1 duplicate).
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		snap := metrics.Snapshot()
		if snap["events_processed_total"]+snap["events_duplicate_total"] >= int64(len(events)) {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	snap := metrics.Snapshot()
	if snap["events_processed_total"] != 6 || snap["events_duplicate_total"] != 1 || snap["events_out_of_order_total"] != 1 {
		t.Fatalf("unexpected event metrics snapshot: %+v", snap)
	}

	// 5. Verify risk endpoints
	for _, ep := range []string{"/api/v1/risks/suppliers", "/api/v1/risks/routes", "/api/v1/risks/shortages"} {
		resp, raw := doJSON(t, http.MethodGet, ts.URL+ep, "op-key", nil, nil)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("GET %s failed: %d %s", ep, resp.StatusCode, string(raw))
		}
	}

	// 6. Verify correlated incident & timeline
	resp, raw = doJSON(t, http.MethodGet, ts.URL+"/api/v1/incidents", "op-key", nil, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /incidents: %d %s", resp.StatusCode, string(raw))
	}
	var incList struct {
		Items []domain.CorrelatedIncident `json:"items"`
	}
	if err := json.Unmarshal(raw, &incList); err != nil || len(incList.Items) == 0 {
		t.Fatalf("expected correlated incident, got len=%d err=%v", len(incList.Items), err)
	}
	incID := incList.Items[0].ID

	resp, raw = doJSON(t, http.MethodGet, ts.URL+"/api/v1/incidents/"+incID+"/timeline", "op-key", nil, nil)
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(raw), "sim-ev-003") {
		t.Fatalf("GET timeline failed: %d %s", resp.StatusCode, string(raw))
	}

	// 7. Investigate incident -> produces summary & drafts PENDING_APPROVAL proposal
	resp, raw = doJSON(t, http.MethodPost, ts.URL+"/api/v1/incidents/"+incID+"/investigate", "op-key", nil, map[string]any{"draft_proposal": true})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("POST investigate: %d %s", resp.StatusCode, string(raw))
	}
	var invOut application.InvestigationOutput
	if err := json.Unmarshal(raw, &invOut); err != nil || invOut.DraftProposal == nil {
		t.Fatalf("expected drafted proposal, got err=%v raw=%s", err, string(raw))
	}
	propID := invOut.DraftProposal.ID
	if invOut.DraftProposal.Status != domain.ProposalPendingApproval {
		t.Fatalf("expected PENDING_APPROVAL, got %s", invOut.DraftProposal.Status)
	}

	// 8. Attempt to execute unapproved proposal -> 409 Conflict (approval-required)
	resp, raw = doJSON(t, http.MethodPost, ts.URL+"/api/v1/proposals/"+propID+"/execute", "op-key", nil, nil)
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("expected 409 executing unapproved proposal, got %d: %s", resp.StatusCode, string(raw))
	}

	// 9. Operator attempting to approve -> 403 Forbidden (RBAC)
	resp, _ = doJSON(t, http.MethodPost, ts.URL+"/api/v1/proposals/"+propID+"/approve", "op-key", nil, map[string]string{"comment": "self-approve"})
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 when operator tries to approve, got %d", resp.StatusCode)
	}

	// 10. Dedicated AI_SYSTEM credential attempting to approve -> 403 Forbidden (autonomous execution invariant!)
	resp, _ = doJSON(t, http.MethodPost, ts.URL+"/api/v1/proposals/"+propID+"/approve", "ai-key", nil, map[string]string{"comment": "auto"})
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 when AI_SYSTEM credential tries to approve, got %d", resp.StatusCode)
	}

	// 10b. Attempted X-Actor-Type or X-Actor-ID header override -> 403 Forbidden (identity integrity!)
	resp, _ = doJSON(t, http.MethodPost, ts.URL+"/api/v1/proposals/"+propID+"/approve", "app-key", map[string]string{"X-Actor-ID": "spoofed@corp.io"}, map[string]string{"comment": "spoof"})
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 when client supplies X-Actor-ID override header, got %d", resp.StatusCode)
	}

	// 11. Human approver approves using authenticated credential identity -> 200 OK (APPROVED)
	resp, raw = doJSON(t, http.MethodPost, ts.URL+"/api/v1/proposals/"+propID+"/approve", "app-key", nil, map[string]string{"comment": "Approved mitigation"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 human approval, got %d: %s", resp.StatusCode, string(raw))
	}

	// 12. Execute approved proposal -> 200 OK (EXECUTED)
	resp, raw = doJSON(t, http.MethodPost, ts.URL+"/api/v1/proposals/"+propID+"/execute", "op-key", nil, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 executing approved proposal, got %d: %s", resp.StatusCode, string(raw))
	}
	var executed domain.ChangeProposal
	if err := json.Unmarshal(raw, &executed); err != nil || executed.Status != domain.ProposalExecuted {
		t.Fatalf("expected EXECUTED proposal, got %+v", executed)
	}
	if executed.ApprovedBy != "approver@supply-chain" || executed.ExecutedBy != "operator@supply-chain" {
		t.Fatalf("expected credential-derived audit actors, got approved_by=%q executed_by=%q", executed.ApprovedBy, executed.ExecutedBy)
	}

	// 13. Verify audit log & alerts & Prometheus /metrics
	resp, raw = doJSON(t, http.MethodGet, ts.URL+"/api/v1/audit", "op-key", nil, nil)
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(raw), "PROPOSAL_EXECUTED") {
		t.Fatalf("expected PROPOSAL_EXECUTED in audit log, got %d %s", resp.StatusCode, string(raw))
	}

	resp, raw = doJSON(t, http.MethodGet, ts.URL+"/metrics", "", nil, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 /metrics, got %d", resp.StatusCode)
	}
	metricsBody := string(raw)
	for _, metricName := range []string{
		"events_ingested_total",
		"events_processed_total",
		"events_duplicate_total",
		"events_out_of_order_total",
		"events_dlq_total",
		"event_processing_duration_seconds_bucket",
		"queue_depth",
		"active_incidents",
		"ai_invocations_total",
	} {
		if !strings.Contains(metricsBody, metricName) {
			t.Fatalf("expected metric %q in /metrics output", metricName)
		}
	}
}

func TestHTTPAPI_UnavailableAI_GracefulDegradation(t *testing.T) {
	ts, _, metrics := setupTestServer(t, &failingInvestigator{})
	now := time.Now().UTC()
	ds := application.DefaultSeedDataset(now)

	// Seed and trigger disruption
	doJSON(t, http.MethodPost, ts.URL+"/api/v1/suppliers", "op-key", nil, ds.Suppliers[0])
	doJSON(t, http.MethodPost, ts.URL+"/api/v1/suppliers", "op-key", nil, ds.Suppliers[1])
	doJSON(t, http.MethodPost, ts.URL+"/api/v1/inventory", "op-key", nil, ds.Inventory[0])
	doJSON(t, http.MethodPost, ts.URL+"/api/v1/events", "op-key", nil, domain.SupplyEvent{
		EventID:   "ev-deg-1",
		EventType: domain.EventSupplierDisruption,
		EntityID:  "SUP-TW-SEMI",
		Payload:   map[string]any{"severity": 90},
	})

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if metrics.Snapshot()["events_processed_total"] >= 1 {
			break
		}
		time.Sleep(15 * time.Millisecond)
	}

	resp, raw := doJSON(t, http.MethodPost, ts.URL+"/api/v1/incidents/inc-sup-tw-semi/investigate", "op-key", nil, map[string]any{"draft_proposal": true})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected graceful 200 degradation when AI is down, got %d: %s", resp.StatusCode, string(raw))
	}
	var out application.InvestigationOutput
	if err := json.Unmarshal(raw, &out); err != nil {
		t.Fatalf("unmarshal degraded output: %v", err)
	}
	if !out.Investigation.Degraded {
		t.Fatal("expected Investigation.Degraded=true when AI provider fails")
	}
	if out.DraftProposal == nil || out.DraftProposal.Status != domain.ProposalPendingApproval {
		t.Fatalf("expected degraded draft proposal still PENDING_APPROVAL, got %+v", out.DraftProposal)
	}
}

func TestHTTPAPI_ActorIdentityIntegrity_PreventsImpersonation(t *testing.T) {
	ts, _, _ := setupTestServer(t, ai.NewNoneInvestigator())
	now := time.Now().UTC()
	ds := application.DefaultSeedDataset(now)

	// Seed a supplier and incident via legitimate operator key.
	resp, raw := doJSON(t, http.MethodPost, ts.URL+"/api/v1/suppliers", "op-key", nil, ds.Suppliers[0])
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("seed supplier: %d %s", resp.StatusCode, string(raw))
	}

	// 1. Operator key holder attempts to impersonate admin@supply-chain via X-Actor-ID -> 403 Forbidden.
	resp, raw = doJSON(t, http.MethodPost, ts.URL+"/api/v1/proposals", "op-key", map[string]string{
		"X-Actor-ID": "admin@supply-chain",
	}, map[string]any{
		"incident_id":      "inc-1",
		"proposal_type":    domain.ProposalTriggerEmergencyReorder,
		"target_entity_id": "SKU-MCU-01",
		"parameters":       map[string]any{"order_units": 500},
		"summary":          "Spoofed proposal",
		"rationale":        "Test impersonation",
	})
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 when operator tries X-Actor-ID impersonation, got %d: %s", resp.StatusCode, string(raw))
	}
	if !strings.Contains(string(raw), "identity-override-forbidden") {
		t.Fatalf("expected identity-override-forbidden problem type, got %s", string(raw))
	}

	// 2. AI service key holder attempts to spoof X-Actor-Type: HUMAN -> 403 Forbidden.
	resp, raw = doJSON(t, http.MethodPost, ts.URL+"/api/v1/proposals", "ai-key", map[string]string{
		"X-Actor-Type": "HUMAN",
	}, map[string]any{
		"incident_id":      "inc-1",
		"proposal_type":    domain.ProposalTriggerEmergencyReorder,
		"target_entity_id": "SKU-MCU-01",
		"parameters":       map[string]any{"order_units": 500},
		"summary":          "Spoofed human AI proposal",
		"rationale":        "Test type spoofing",
	})
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 when ai-key sends X-Actor-Type: HUMAN, got %d: %s", resp.StatusCode, string(raw))
	}

	// 3. AI service key holder legitimately drafts a proposal -> recorded as ai-advisor@supply-chain (AI_SYSTEM).
	resp, raw = doJSON(t, http.MethodPost, ts.URL+"/api/v1/proposals", "ai-key", nil, map[string]any{
		"incident_id":      "inc-1",
		"proposal_type":    domain.ProposalTriggerEmergencyReorder,
		"target_entity_id": "SKU-MCU-01",
		"parameters":       map[string]any{"order_units": 500},
		"summary":          "AI drafted emergency reorder",
		"rationale":        "Elevated supplier risk",
	})
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201 when ai-key drafts proposal, got %d: %s", resp.StatusCode, string(raw))
	}
	var prop domain.ChangeProposal
	if err := json.Unmarshal(raw, &prop); err != nil {
		t.Fatalf("unmarshal proposal: %v", err)
	}
	if prop.ProposedBy != "ai-advisor@supply-chain" || prop.ProposedByType != domain.ActorAISystem {
		t.Fatalf("expected server-derived AI actor, got actor=%q type=%q", prop.ProposedBy, prop.ProposedByType)
	}

	// 4. AI service key holder cannot approve, reject, or execute its own proposal -> 403 Forbidden.
	for _, action := range []string{"approve", "reject", "execute"} {
		resp, _ = doJSON(t, http.MethodPost, ts.URL+"/api/v1/proposals/"+prop.ID+"/"+action, "ai-key", nil, map[string]string{"comment": "auto"})
		if resp.StatusCode != http.StatusForbidden {
			t.Fatalf("expected 403 when ai-key calls /%s, got %d", action, resp.StatusCode)
		}
	}

	// 5. Verify durable audit log recorded the rejected impersonation attempts under the TRUE credential identities.
	resp, raw = doJSON(t, http.MethodGet, ts.URL+"/api/v1/audit", "adm-key", nil, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /audit: %d %s", resp.StatusCode, string(raw))
	}
	var auditResp struct {
		Items []domain.AuditEntry `json:"items"`
	}
	if err := json.Unmarshal(raw, &auditResp); err != nil {
		t.Fatalf("unmarshal audit: %v", err)
	}

	var foundOpRejection, foundAIRejection bool
	for _, entry := range auditResp.Items {
		if entry.Action == "IDENTITY_OVERRIDE_REJECTED" && entry.Actor == "operator@supply-chain" && entry.ActorType == domain.ActorHuman {
			foundOpRejection = true
		}
		if entry.Action == "IDENTITY_OVERRIDE_REJECTED" && entry.Actor == "ai-advisor@supply-chain" && entry.ActorType == domain.ActorAISystem {
			foundAIRejection = true
		}
		if entry.Action == "PROPOSAL_CREATED" && entry.Actor == "admin@supply-chain" {
			t.Fatalf("impersonated admin actor leaked into PROPOSAL_CREATED audit record: %+v", entry)
		}
	}
	if !foundOpRejection || !foundAIRejection {
		t.Fatalf("expected IDENTITY_OVERRIDE_REJECTED audit entries for true actors (op=%v ai=%v), entries=%+v", foundOpRejection, foundAIRejection, auditResp.Items)
	}
}
