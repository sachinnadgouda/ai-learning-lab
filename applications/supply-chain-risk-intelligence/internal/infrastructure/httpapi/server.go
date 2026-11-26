package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

type ServerDeps struct {
	Repo          application.Repository
	Broker        application.EventBroker
	Processor     *application.Processor
	Investigator  *application.InvestigationService
	Governance    *application.GovernanceService
	Engine        *domain.RiskEngine
	Metrics       *application.Metrics
	Auth          *KeyAuthenticator
	RateLimiter   *IPRateLimiter
	AllowedOrigin []string
	Logger        *slog.Logger
}

// NewHandler constructs the versioned HTTP API handler using Go 1.22+ enhanced net/http routing.
func NewHandler(deps ServerDeps) http.Handler {
	if deps.Engine == nil {
		deps.Engine = domain.NewRiskEngine()
	}
	if deps.Metrics == nil {
		deps.Metrics = application.NewMetrics()
	}
	if deps.Logger == nil {
		deps.Logger = slog.Default()
	}

	s := &server{deps: deps}
	mux := http.NewServeMux()

	anyAuth := deps.Auth.RequireRoles(RoleOperator, RoleApprover, RoleAdmin, RoleAIService)
	humanAuth := deps.Auth.RequireRoles(RoleOperator, RoleApprover, RoleAdmin)
	opsOrAdmin := deps.Auth.RequireRoles(RoleOperator, RoleAdmin)
	approverOrAdmin := deps.Auth.RequireRoles(RoleApprover, RoleAdmin)
	// Attach audit sink automatically if caller did not configure one.
	if deps.Repo != nil {
		deps.Auth.WithAuditSink(deps.Repo)
	}

	// Operational endpoints (unauthenticated for probes & Prometheus scraping)
	mux.HandleFunc("GET /health", s.handleHealth)
	mux.HandleFunc("GET /ready", s.handleReady)
	mux.HandleFunc("GET /metrics", s.handleMetrics)

	// Entity CRUD / Seeding
	mux.HandleFunc("GET /api/v1/suppliers", anyAuth(s.handleListSuppliers))
	mux.HandleFunc("GET /api/v1/suppliers/{id}", anyAuth(s.handleGetSupplier))
	mux.HandleFunc("POST /api/v1/suppliers", opsOrAdmin(s.handleUpsertSupplier))

	mux.HandleFunc("GET /api/v1/routes", anyAuth(s.handleListRoutes))
	mux.HandleFunc("GET /api/v1/routes/{id}", anyAuth(s.handleGetRoute))
	mux.HandleFunc("POST /api/v1/routes", opsOrAdmin(s.handleUpsertRoute))

	mux.HandleFunc("GET /api/v1/shipments", anyAuth(s.handleListShipments))
	mux.HandleFunc("GET /api/v1/shipments/{id}", anyAuth(s.handleGetShipment))
	mux.HandleFunc("POST /api/v1/shipments", opsOrAdmin(s.handleUpsertShipment))

	mux.HandleFunc("GET /api/v1/inventory", anyAuth(s.handleListInventory))
	mux.HandleFunc("GET /api/v1/inventory/{sku}", anyAuth(s.handleGetInventory))
	mux.HandleFunc("POST /api/v1/inventory", opsOrAdmin(s.handleUpsertInventory))

	// Event ingestion (single or batch)
	mux.HandleFunc("POST /api/v1/events", anyAuth(s.handleIngestEvents))

	// Deterministic risk assessments
	mux.HandleFunc("GET /api/v1/risks/suppliers", anyAuth(s.handleSupplierRisks))
	mux.HandleFunc("GET /api/v1/risks/routes", anyAuth(s.handleRouteRisks))
	mux.HandleFunc("GET /api/v1/risks/shortages", anyAuth(s.handleShortages))

	// Correlated incidents & AI investigation
	mux.HandleFunc("GET /api/v1/incidents", anyAuth(s.handleListIncidents))
	mux.HandleFunc("GET /api/v1/incidents/{id}/timeline", anyAuth(s.handleIncidentTimeline))
	mux.HandleFunc("POST /api/v1/incidents/{id}/investigate", anyAuth(s.handleInvestigateIncident))

	// Human-in-the-loop Change Proposals
	mux.HandleFunc("GET /api/v1/proposals", anyAuth(s.handleListProposals))
	mux.HandleFunc("POST /api/v1/proposals", anyAuth(s.handleCreateProposal))
	mux.HandleFunc("POST /api/v1/proposals/{id}/approve", approverOrAdmin(s.handleApproveProposal))
	mux.HandleFunc("POST /api/v1/proposals/{id}/reject", approverOrAdmin(s.handleRejectProposal))
	mux.HandleFunc("POST /api/v1/proposals/{id}/execute", humanAuth(s.handleExecuteProposal))

	// DLQ, Audit, Alerts
	mux.HandleFunc("GET /api/v1/dlq", anyAuth(s.handleListDLQ))
	mux.HandleFunc("POST /api/v1/dlq/{id}/replay", opsOrAdmin(s.handleReplayDLQ))
	mux.HandleFunc("GET /api/v1/audit", anyAuth(s.handleListAudit))
	mux.HandleFunc("GET /api/v1/alerts", anyAuth(s.handleListAlerts))

	return WrapMiddleware(mux, deps.AllowedOrigin, deps.RateLimiter, deps.Metrics, deps.Logger)
}

type server struct {
	deps ServerDeps
}

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, r, http.StatusOK, map[string]any{
		"status": "ok",
		"time":   time.Now().UTC().Format(time.RFC3339),
	})
}

func (s *server) handleReady(w http.ResponseWriter, r *http.Request) {
	checks := map[string]string{
		"sqlite": "ok",
		"broker": "ok",
	}
	ready := true

	if err := s.deps.Repo.Ping(r.Context()); err != nil {
		checks["sqlite"] = "degraded"
		ready = false
	}
	if s.deps.Broker != nil {
		if err := s.deps.Broker.Ping(r.Context()); err != nil {
			checks["broker"] = "degraded"
			ready = false
		}
	}

	status := http.StatusOK
	state := "ready"
	if !ready {
		status = http.StatusServiceUnavailable
		state = "degraded"
	}
	writeJSON(w, r, status, map[string]any{
		"status":         state,
		"broker_backend": brokerName(s.deps.Broker),
		"queue_depth":    brokerDepth(s.deps.Broker),
		"checks":         checks,
	})
}

func (s *server) handleMetrics(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, s.deps.Metrics.RenderPrometheus())
}

// --- Entity handlers ---

func (s *server) handleListSuppliers(w http.ResponseWriter, r *http.Request) {
	items, err := s.deps.Repo.ListSuppliers(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (s *server) handleGetSupplier(w http.ResponseWriter, r *http.Request) {
	item, err := s.deps.Repo.GetSupplier(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, item)
}

func (s *server) handleUpsertSupplier(w http.ResponseWriter, r *http.Request) {
	var sup domain.Supplier
	if err := decodeJSON(r, &sup); err != nil {
		writeProblem(w, r, http.StatusBadRequest, "invalid-json", "Invalid Request Body", err.Error())
		return
	}
	if strings.TrimSpace(sup.ID) == "" || strings.TrimSpace(sup.Name) == "" {
		writeProblem(w, r, http.StatusBadRequest, "validation-error", "Validation Failed", "id and name are required.")
		return
	}
	if sup.Status == "" {
		sup.Status = domain.SupplierOperational
	}
	if sup.Tier <= 0 {
		sup.Tier = 2
	}
	sup.UpdatedAt = time.Now().UTC()
	if err := s.deps.Repo.UpsertSupplier(r.Context(), sup); err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusCreated, sup)
}

func (s *server) handleListRoutes(w http.ResponseWriter, r *http.Request) {
	items, err := s.deps.Repo.ListRoutes(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (s *server) handleGetRoute(w http.ResponseWriter, r *http.Request) {
	item, err := s.deps.Repo.GetRoute(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, item)
}

func (s *server) handleUpsertRoute(w http.ResponseWriter, r *http.Request) {
	var rt domain.Route
	if err := decodeJSON(r, &rt); err != nil {
		writeProblem(w, r, http.StatusBadRequest, "invalid-json", "Invalid Request Body", err.Error())
		return
	}
	if strings.TrimSpace(rt.ID) == "" || strings.TrimSpace(rt.Name) == "" {
		writeProblem(w, r, http.StatusBadRequest, "validation-error", "Validation Failed", "id and name are required.")
		return
	}
	if rt.Status == "" {
		rt.Status = domain.RouteClear
	}
	if rt.Mode == "" {
		rt.Mode = "OCEAN"
	}
	if rt.CostMultiplier <= 0 {
		rt.CostMultiplier = 1.0
	}
	rt.UpdatedAt = time.Now().UTC()
	if err := s.deps.Repo.UpsertRoute(r.Context(), rt); err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusCreated, rt)
}

func (s *server) handleListShipments(w http.ResponseWriter, r *http.Request) {
	items, err := s.deps.Repo.ListShipments(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (s *server) handleGetShipment(w http.ResponseWriter, r *http.Request) {
	item, err := s.deps.Repo.GetShipment(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, item)
}

func (s *server) handleUpsertShipment(w http.ResponseWriter, r *http.Request) {
	var sh domain.Shipment
	if err := decodeJSON(r, &sh); err != nil {
		writeProblem(w, r, http.StatusBadRequest, "invalid-json", "Invalid Request Body", err.Error())
		return
	}
	if strings.TrimSpace(sh.ID) == "" || strings.TrimSpace(sh.SKU) == "" {
		writeProblem(w, r, http.StatusBadRequest, "validation-error", "Validation Failed", "id and sku are required.")
		return
	}
	if sh.Status == "" {
		sh.Status = domain.ShipmentInTransit
	}
	now := time.Now().UTC()
	if sh.ScheduledETA.IsZero() {
		sh.ScheduledETA = now.Add(7 * 24 * time.Hour)
	}
	if sh.CurrentETA.IsZero() {
		sh.CurrentETA = sh.ScheduledETA
	}
	sh.UpdatedAt = now
	if err := s.deps.Repo.UpsertShipment(r.Context(), sh); err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusCreated, sh)
}

func (s *server) handleListInventory(w http.ResponseWriter, r *http.Request) {
	items, err := s.deps.Repo.ListInventoryItems(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (s *server) handleGetInventory(w http.ResponseWriter, r *http.Request) {
	item, err := s.deps.Repo.GetInventoryItem(r.Context(), r.PathValue("sku"))
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, item)
}

func (s *server) handleUpsertInventory(w http.ResponseWriter, r *http.Request) {
	var item domain.InventoryItem
	if err := decodeJSON(r, &item); err != nil {
		writeProblem(w, r, http.StatusBadRequest, "invalid-json", "Invalid Request Body", err.Error())
		return
	}
	if strings.TrimSpace(item.SKU) == "" || strings.TrimSpace(item.Name) == "" {
		writeProblem(w, r, http.StatusBadRequest, "validation-error", "Validation Failed", "sku and name are required.")
		return
	}
	if item.SafetyStockDays <= 0 {
		item.SafetyStockDays = 7
	}
	item.UpdatedAt = time.Now().UTC()
	if err := s.deps.Repo.UpsertInventoryItem(r.Context(), item); err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusCreated, item)
}

// --- Event ingestion ---

func (s *server) handleIngestEvents(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeProblem(w, r, http.StatusBadRequest, "read-error", "Bad Request", "Unable to read request body.")
		return
	}
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) == 0 {
		writeProblem(w, r, http.StatusBadRequest, "empty-body", "Bad Request", "Request body cannot be empty.")
		return
	}

	var events []domain.SupplyEvent
	if trimmed[0] == '[' {
		if err := json.Unmarshal(trimmed, &events); err != nil {
			writeProblem(w, r, http.StatusBadRequest, "invalid-json", "Invalid Event Batch", err.Error())
			return
		}
	} else {
		var wrapper struct {
			Events []domain.SupplyEvent `json:"events"`
		}
		if err := json.Unmarshal(trimmed, &wrapper); err == nil && len(wrapper.Events) > 0 {
			events = wrapper.Events
		} else {
			var single domain.SupplyEvent
			if err := json.Unmarshal(trimmed, &single); err != nil {
				writeProblem(w, r, http.StatusBadRequest, "invalid-json", "Invalid Event Payload", err.Error())
				return
			}
			events = []domain.SupplyEvent{single}
		}
	}

	if len(events) == 0 || len(events) > 500 {
		writeProblem(w, r, http.StatusBadRequest, "invalid-batch-size", "Invalid Event Batch Size", "Provide between 1 and 500 events per request.")
		return
	}

	corrID := CorrelationIDFromContext(r.Context())
	acceptedIDs := make([]string, 0, len(events))
	for _, ev := range events {
		ev.CorrelationID = corrID
		enqueued, err := s.deps.Processor.Enqueue(r.Context(), ev)
		if err != nil {
			if errors.Is(err, domain.ErrQueueFull) {
				w.Header().Set("Retry-After", "1")
				writeProblem(w, r, http.StatusTooManyRequests, "queue-full", "Event Queue Saturated", "Bounded event intake queue is full; retry with backoff.")
				return
			}
			s.writeDomainError(w, r, err)
			return
		}
		acceptedIDs = append(acceptedIDs, enqueued.EventID)
	}

	writeJSON(w, r, http.StatusAccepted, map[string]any{
		"status":         "accepted",
		"accepted":       len(acceptedIDs),
		"event_ids":      acceptedIDs,
		"correlation_id": corrID,
	})
}

// --- Risk Endpoints ---

func (s *server) handleSupplierRisks(w http.ResponseWriter, r *http.Request) {
	suppliers, err := s.deps.Repo.ListSuppliers(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	now := time.Now().UTC()
	scores := make([]domain.RiskScore, 0, len(suppliers))
	for _, sup := range suppliers {
		scores = append(scores, s.deps.Engine.ScoreSupplier(sup, now))
	}
	sort.Slice(scores, func(i, j int) bool {
		if scores[i].Score != scores[j].Score {
			return scores[i].Score > scores[j].Score
		}
		return scores[i].EntityID < scores[j].EntityID
	})
	writeJSON(w, r, http.StatusOK, map[string]any{"items": scores, "count": len(scores)})
}

func (s *server) handleRouteRisks(w http.ResponseWriter, r *http.Request) {
	routes, err := s.deps.Repo.ListRoutes(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	shipments, err := s.deps.Repo.ListShipments(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	delayedByRoute := make(map[string]int)
	for _, sh := range shipments {
		if sh.Status == domain.ShipmentDelayed || sh.DelayHours > 0 {
			delayedByRoute[sh.RouteID]++
		}
	}
	now := time.Now().UTC()
	scores := make([]domain.RiskScore, 0, len(routes))
	for _, rt := range routes {
		scores = append(scores, s.deps.Engine.ScoreRoute(rt, delayedByRoute[rt.ID], now))
	}
	sort.Slice(scores, func(i, j int) bool {
		if scores[i].Score != scores[j].Score {
			return scores[i].Score > scores[j].Score
		}
		return scores[i].EntityID < scores[j].EntityID
	})
	writeJSON(w, r, http.StatusOK, map[string]any{"items": scores, "count": len(scores)})
}

func (s *server) handleShortages(w http.ResponseWriter, r *http.Request) {
	items, err := s.deps.Repo.ListInventoryItems(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	shipments, err := s.deps.Repo.ListShipments(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	alerts := s.deps.Engine.DetectShortages(items, shipments, time.Now().UTC())
	writeJSON(w, r, http.StatusOK, map[string]any{"items": alerts, "count": len(alerts)})
}

// --- Incidents & Investigation ---

func (s *server) handleListIncidents(w http.ResponseWriter, r *http.Request) {
	items, err := s.deps.Repo.ListIncidents(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (s *server) handleIncidentTimeline(w http.ResponseWriter, r *http.Request) {
	inc, err := s.deps.Repo.GetIncident(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, map[string]any{
		"incident_id":     inc.ID,
		"title":           inc.Title,
		"severity":        inc.Severity,
		"risk_score":      inc.RiskScore,
		"timeline":        inc.Timeline,
		"shortage_alerts": inc.ShortageAlerts,
		"recommendations": inc.Recommendations,
	})
}

func (s *server) handleInvestigateIncident(w http.ResponseWriter, r *http.Request) {
	var body struct {
		DraftProposal *bool `json:"draft_proposal"`
	}
	// Body is optional; defaults to draft_proposal=true.
	if r.Body != nil {
		raw, _ := io.ReadAll(r.Body)
		if len(bytes.TrimSpace(raw)) > 0 {
			if err := json.Unmarshal(raw, &body); err != nil {
				writeProblem(w, r, http.StatusBadRequest, "invalid-json", "Invalid Request Body", err.Error())
				return
			}
		}
	}
	draft := true
	if body.DraftProposal != nil {
		draft = *body.DraftProposal
	}
	principal, _ := PrincipalFromContext(r.Context())
	out, err := s.deps.Investigator.InvestigateIncident(
		r.Context(),
		r.PathValue("id"),
		draft,
		principal.Actor,
		CorrelationIDFromContext(r.Context()),
	)
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, out)
}

// --- Proposals (Human-in-the-Loop Governance) ---

func (s *server) handleListProposals(w http.ResponseWriter, r *http.Request) {
	items, err := s.deps.Repo.ListProposals(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (s *server) handleCreateProposal(w http.ResponseWriter, r *http.Request) {
	var req struct {
		IncidentID     string              `json:"incident_id"`
		ProposalType   domain.ProposalType `json:"proposal_type"`
		TargetEntityID string              `json:"target_entity_id"`
		Parameters     map[string]any      `json:"parameters"`
		Summary        string              `json:"summary"`
		Rationale      string              `json:"rationale"`
	}
	if err := decodeJSON(r, &req); err != nil {
		writeProblem(w, r, http.StatusBadRequest, "invalid-json", "Invalid Request Body", err.Error())
		return
	}
	principal, _ := PrincipalFromContext(r.Context())
	p, err := s.deps.Governance.CreateProposal(
		r.Context(),
		req.IncidentID,
		req.ProposalType,
		req.TargetEntityID,
		req.Parameters,
		req.Summary,
		req.Rationale,
		principal.Actor,
		principal.ActorType,
		CorrelationIDFromContext(r.Context()),
	)
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusCreated, p)
}

func (s *server) handleApproveProposal(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Comment string `json:"comment"`
	}
	if r.Body != nil {
		raw, _ := io.ReadAll(r.Body)
		if len(bytes.TrimSpace(raw)) > 0 {
			_ = json.Unmarshal(raw, &req)
		}
	}
	principal, _ := PrincipalFromContext(r.Context())
	p, err := s.deps.Governance.ApproveProposal(
		r.Context(),
		r.PathValue("id"),
		principal.Actor,
		principal.ActorType,
		req.Comment,
		CorrelationIDFromContext(r.Context()),
	)
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, p)
}

func (s *server) handleRejectProposal(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Reason string `json:"reason"`
	}
	if r.Body != nil {
		raw, _ := io.ReadAll(r.Body)
		if len(bytes.TrimSpace(raw)) > 0 {
			_ = json.Unmarshal(raw, &req)
		}
	}
	principal, _ := PrincipalFromContext(r.Context())
	p, err := s.deps.Governance.RejectProposal(
		r.Context(),
		r.PathValue("id"),
		principal.Actor,
		principal.ActorType,
		req.Reason,
		CorrelationIDFromContext(r.Context()),
	)
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, p)
}

func (s *server) handleExecuteProposal(w http.ResponseWriter, r *http.Request) {
	principal, _ := PrincipalFromContext(r.Context())
	p, err := s.deps.Governance.ExecuteProposal(
		r.Context(),
		r.PathValue("id"),
		principal.Actor,
		principal.ActorType,
		CorrelationIDFromContext(r.Context()),
	)
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, p)
}

// --- DLQ, Audit, Alerts ---

func (s *server) handleListDLQ(w http.ResponseWriter, r *http.Request) {
	items, err := s.deps.Repo.ListDLQ(r.Context())
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (s *server) handleReplayDLQ(w http.ResponseWriter, r *http.Request) {
	principal, _ := PrincipalFromContext(r.Context())
	dlq, err := s.deps.Processor.ReplayDLQ(
		r.Context(),
		r.PathValue("id"),
		principal.Actor,
		CorrelationIDFromContext(r.Context()),
	)
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusAccepted, dlq)
}

func (s *server) handleListAudit(w http.ResponseWriter, r *http.Request) {
	limit := parseLimit(r, 100)
	items, err := s.deps.Repo.ListAudit(r.Context(), limit)
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (s *server) handleListAlerts(w http.ResponseWriter, r *http.Request) {
	limit := parseLimit(r, 100)
	items, err := s.deps.Repo.ListAlerts(r.Context(), limit)
	if err != nil {
		s.writeDomainError(w, r, err)
		return
	}
	writeJSON(w, r, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

// --- Helpers ---

func decodeJSON(r *http.Request, dst any) error {
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(dst); err != nil {
		return err
	}
	return nil
}

func parseLimit(r *http.Request, fallback int) int {
	raw := strings.TrimSpace(r.URL.Query().Get("limit"))
	if raw == "" {
		return fallback
	}
	if n, err := strconv.Atoi(raw); err == nil && n > 0 && n <= 500 {
		return n
	}
	return fallback
}

func (s *server) writeDomainError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrNotFound):
		writeProblem(w, r, http.StatusNotFound, "not-found", "Resource Not Found", err.Error())
	case errors.Is(err, domain.ErrInvalidInput):
		writeProblem(w, r, http.StatusBadRequest, "invalid-input", "Invalid Input", err.Error())
	case errors.Is(err, domain.ErrApprovalRequired):
		writeProblem(w, r, http.StatusConflict, "approval-required", "Human Approval Required", err.Error())
	case errors.Is(err, domain.ErrAutonomousExecutionForbidden):
		writeProblem(w, r, http.StatusForbidden, "autonomous-execution-forbidden", "Autonomous Execution Forbidden", err.Error())
	case errors.Is(err, domain.ErrInvalidTransition):
		writeProblem(w, r, http.StatusConflict, "invalid-transition", "Invalid State Transition", err.Error())
	case errors.Is(err, domain.ErrQueueFull):
		w.Header().Set("Retry-After", "1")
		writeProblem(w, r, http.StatusTooManyRequests, "queue-full", "Event Queue Full", err.Error())
	case errors.Is(err, domain.ErrUpstreamUnavailable):
		writeProblem(w, r, http.StatusServiceUnavailable, "upstream-unavailable", "Upstream Dependency Unavailable", "Upstream service is temporarily unavailable.")
	default:
		s.deps.Logger.Error("unhandled_api_error", "path", r.URL.Path, "error", err)
		writeProblem(w, r, http.StatusInternalServerError, "internal-error", "Internal Server Error", "An unexpected internal error occurred.")
	}
}

func brokerName(b application.EventBroker) string {
	if b == nil {
		return "none"
	}
	return b.Name()
}

func brokerDepth(b application.EventBroker) int {
	if b == nil {
		return 0
	}
	return b.Depth()
}
