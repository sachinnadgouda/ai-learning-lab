package httpapi

import (
	"encoding/json"
	"net/http"
)

// ProblemDetails conforms to RFC 9457 (application/problem+json).
type ProblemDetails struct {
	Type          string `json:"type"`
	Title         string `json:"title"`
	Status        int    `json:"status"`
	Detail        string `json:"detail"`
	Instance      string `json:"instance,omitempty"`
	CorrelationID string `json:"correlation_id,omitempty"`
}

func writeProblem(w http.ResponseWriter, r *http.Request, status int, problemType, title, detail string) {
	corrID := CorrelationIDFromContext(r.Context())
	pd := ProblemDetails{
		Type:          "https://supply-chain-risk.local/problems/" + problemType,
		Title:         title,
		Status:        status,
		Detail:        detail,
		Instance:      r.URL.Path,
		CorrelationID: corrID,
	}
	w.Header().Set("Content-Type", "application/problem+json")
	if corrID != "" {
		w.Header().Set("X-Correlation-ID", corrID)
	}
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(pd)
}

func writeJSON(w http.ResponseWriter, r *http.Request, status int, payload any) {
	corrID := CorrelationIDFromContext(r.Context())
	w.Header().Set("Content-Type", "application/json")
	if corrID != "" {
		w.Header().Set("X-Correlation-ID", corrID)
	}
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
