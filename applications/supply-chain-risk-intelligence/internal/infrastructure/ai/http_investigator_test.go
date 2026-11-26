package ai

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

func sampleInvestigationReq() application.InvestigationRequest {
	return application.InvestigationRequest{
		Incident: domain.CorrelatedIncident{
			ID:                  "inc-sup-tw-semi",
			Title:               "Supply Chain Cascade: Formosa Precision Silicon",
			Severity:            domain.SeverityCritical,
			RiskScore:           88.5,
			RootSupplierID:      "SUP-TW-SEMI",
			AffectedRouteIDs:    []string{"RT-OCEAN-TW-RTM"},
			AffectedShipmentIDs: []string{"SHP-1001"},
			AffectedSKUs:        []string{"SKU-MCU-01"},
			Summary:             "Supplier disruption linked to port congestion and MCU shortage.",
		},
		FencedNotes:      []string{`<untrusted_data source="ev-1">Vessel delayed 7 days.</untrusted_data>`},
		InjectionFlagged: false,
		CorrelationID:    "corr-test-1",
	}
}

func TestHTTPInvestigator_OllamaValidStructuredJSON(t *testing.T) {
	var capturedBody string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/chat" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		b, _ := io.ReadAll(r.Body)
		capturedBody = string(b)

		resp := map[string]any{
			"message": map[string]string{
				"content": `{"summary":"Typhoon congestion and wafer fab outage threaten SKU-MCU-01 stockout.","root_cause_hypothesis":"SUP-TW-SEMI outage compounded by Kaohsiung berth backlog.","recommended_action_type":"REROUTE_SHIPMENT","recommended_target_id":"SHP-1001","rationale":"Air freight cuts transit by 24 days.","confidence":0.91}`,
			},
			"prompt_eval_count": 320,
			"eval_count":        95,
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer ts.Close()

	inv := NewHTTPInvestigator(HTTPInvestigatorConfig{
		Provider:   "ollama",
		BaseURL:    ts.URL,
		Model:      "llama3.2:1b",
		Timeout:    2 * time.Second,
		MaxRetries: 1,
	})

	res, err := inv.Investigate(context.Background(), sampleInvestigationReq())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if res.RecommendedActionType != string(domain.ProposalRerouteShipment) || res.RecommendedTargetID != "SHP-1001" {
		t.Fatalf("unexpected recommendation: %+v", res)
	}
	if res.PromptTokens != 320 || res.CompletionTokens != 95 {
		t.Fatalf("expected token counts 320/95, got %d/%d", res.PromptTokens, res.CompletionTokens)
	}
	if !strings.Contains(capturedBody, "<untrusted_data") {
		t.Fatalf("expected untrusted_data fencing in prompt body, got %s", capturedBody)
	}
}

func TestHTTPInvestigator_OpenAIRetry429AndMalformedJSON(t *testing.T) {
	var calls atomic.Int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-secret-key" {
			t.Errorf("missing bearer auth header")
		}
		c := calls.Add(1)
		if c == 1 {
			w.Header().Set("Retry-After", "0")
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		if c == 2 {
			// Valid OpenAI response
			_ = json.NewEncoder(w).Encode(map[string]any{
				"choices": []map[string]any{
					{
						"message": map[string]string{
							"content": `{"summary":"Shift allocation to Dresden Fab.","root_cause_hypothesis":"Single-source APAC outage.","recommended_action_type":"SHIFT_SUPPLIER_ALLOCATION","recommended_target_id":"SKU-MCU-01","rationale":"Reduces risk by 60 points.","confidence":0.88}`,
						},
					},
				},
				"usage": map[string]int{"prompt_tokens": 210, "completion_tokens": 70},
			})
			return
		}
		// Malformed / invalid schema response (unknown field / invalid action)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]string{"content": `{"summary":"bad","recommended_action_type":"AUTO_EXECUTE_NOW","extra_field":true}`}},
			},
		})
	}))
	defer ts.Close()

	inv := NewHTTPInvestigator(HTTPInvestigatorConfig{
		Provider:    "openai",
		BaseURL:     ts.URL,
		Model:       "gpt-4o-mini",
		APIKey:      "test-secret-key",
		Timeout:     2 * time.Second,
		MaxRetries:  2,
		BaseBackoff: 10 * time.Millisecond,
	})

	// First call: retries after 429 and succeeds!
	res, err := inv.Investigate(context.Background(), sampleInvestigationReq())
	if err != nil {
		t.Fatalf("expected recovery after 429, got %v", err)
	}
	if res.RecommendedActionType != string(domain.ProposalShiftSupplierAllocation) {
		t.Fatalf("unexpected action: %s", res.RecommendedActionType)
	}

	// Second call: malformed schema rejected!
	_, err = inv.Investigate(context.Background(), sampleInvestigationReq())
	if !errors.Is(err, domain.ErrUpstreamUnavailable) {
		t.Fatalf("expected ErrUpstreamUnavailable on schema violation, got %v", err)
	}
}

func TestHTTPInvestigator_TimeoutAndCircuitBreaker(t *testing.T) {
	var hits atomic.Int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		time.Sleep(150 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}))
	defer ts.Close()

	inv := NewHTTPInvestigator(HTTPInvestigatorConfig{
		Provider:         "ollama",
		BaseURL:          ts.URL,
		Model:            "llama3.2:1b",
		Timeout:          40 * time.Millisecond,
		MaxRetries:       0,
		FailureThreshold: 2,
		BreakerCooldown:  5 * time.Second,
	})

	// Two consecutive timeouts open the circuit breaker.
	for i := 0; i < 2; i++ {
		if _, err := inv.Investigate(context.Background(), sampleInvestigationReq()); !errors.Is(err, domain.ErrUpstreamUnavailable) {
			t.Fatalf("call %d: expected ErrUpstreamUnavailable, got %v", i, err)
		}
	}
	hitsBeforeOpen := hits.Load()

	// Third call must be short-circuited immediately without hitting the upstream server!
	_, err := inv.Investigate(context.Background(), sampleInvestigationReq())
	if !errors.Is(err, domain.ErrUpstreamUnavailable) {
		t.Fatalf("expected circuit breaker ErrUpstreamUnavailable, got %v", err)
	}
	if hits.Load() != hitsBeforeOpen {
		t.Fatalf("expected circuit breaker to prevent upstream hit; hits=%d before=%d", hits.Load(), hitsBeforeOpen)
	}
}
