package application

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

// InvestigationOutput bundles the AI investigation result and any drafted (PENDING_APPROVAL) ChangeProposal.
type InvestigationOutput struct {
	Incident      domain.CorrelatedIncident    `json:"incident"`
	Investigation domain.AIInvestigationResult `json:"investigation"`
	DraftProposal *domain.ChangeProposal       `json:"draft_proposal,omitempty"`
}

// InvestigationService coordinates fenced AI incident investigation with graceful degradation
// and strictly human-gated ChangeProposal drafting.
type InvestigationService struct {
	repo         Repository
	investigator AIInvestigator
	sanitizer    *domain.Sanitizer
	metrics      *Metrics
	logger       *slog.Logger
}

func NewInvestigationService(
	repo Repository,
	investigator AIInvestigator,
	metrics *Metrics,
	logger *slog.Logger,
) *InvestigationService {
	if metrics == nil {
		metrics = NewMetrics()
	}
	if logger == nil {
		logger = slog.Default()
	}
	return &InvestigationService{
		repo:         repo,
		investigator: investigator,
		sanitizer:    domain.NewSanitizer(),
		metrics:      metrics,
		logger:       logger,
	}
}

// InvestigateIncident runs an AI investigation against deterministic incident data + fenced notes.
// If the AI provider fails or is unavailable, it degrades gracefully to a deterministic summary.
// When draftProposal is true, any drafted proposal ALWAYS starts in PENDING_APPROVAL and is never executed.
func (s *InvestigationService) InvestigateIncident(
	ctx context.Context,
	incidentID string,
	draftProposal bool,
	actor string,
	correlationID string,
) (*InvestigationOutput, error) {
	inc, err := s.repo.GetIncident(ctx, incidentID)
	if err != nil {
		return nil, err
	}

	// Fence every untrusted timeline note and scan for prompt injection payloads before any AI call.
	fencedNotes := make([]string, 0, len(inc.Timeline))
	injectionDetected := false
	for _, ev := range inc.Timeline {
		if strings.TrimSpace(ev.Notes) == "" {
			continue
		}
		fenced, detected, matches := s.sanitizer.FenceUntrustedText(ev.EventID, ev.Notes)
		fencedNotes = append(fencedNotes, fenced)
		if detected {
			injectionDetected = true
			s.logger.Warn("prompt_injection_detected_in_event_notes",
				"incident_id", inc.ID,
				"event_id", ev.EventID,
				"match_count", len(matches),
				"correlation_id", correlationID,
			)
		}
	}

	req := InvestigationRequest{
		Incident:         *inc,
		FencedNotes:      fencedNotes,
		InjectionFlagged: injectionDetected,
		CorrelationID:    correlationID,
	}

	providerName := "none"
	if s.investigator != nil {
		providerName = s.investigator.ProviderName()
	}

	var result *domain.AIInvestigationResult
	if s.investigator == nil {
		result = buildDegradedInvestigation(*inc, injectionDetected, "none", "no AI investigator configured")
		s.metrics.IncAIInvocation("none", "degraded")
	} else {
		res, invErr := s.investigator.Investigate(ctx, req)
		if invErr != nil {
			s.logger.Warn("ai_investigation_degraded",
				"incident_id", inc.ID,
				"provider", providerName,
				"error", s.sanitizer.RedactSecrets(invErr.Error()),
				"correlation_id", correlationID,
			)
			result = buildDegradedInvestigation(*inc, injectionDetected, providerName, invErr.Error())
			s.metrics.IncAIInvocation(providerName, "degraded")
		} else {
			result = res
			result.InjectionDetected = result.InjectionDetected || injectionDetected
			outcome := "success"
			if result.Degraded {
				outcome = "degraded"
			}
			s.metrics.IncAIInvocation(providerName, outcome)
		}
	}

	now := time.Now().UTC()
	inc.AIInvestigation = result
	inc.Status = domain.IncidentInvestigating
	inc.UpdatedAt = now
	if err := s.repo.UpsertIncident(ctx, *inc); err != nil {
		return nil, err
	}

	var drafted *domain.ChangeProposal
	if draftProposal {
		propType, targetID, params := deriveSafeProposalSpec(*inc, result)
		if propType.Valid() && targetID != "" {
			p, err := domain.NewChangeProposal(
				"prop-"+uuid.NewString(),
				inc.ID,
				propType,
				targetID,
				params,
				fmt.Sprintf("Draft mitigation for %s: %s on %s", inc.ID, propType, targetID),
				result.Rationale,
				"ai-investigator:"+result.Provider,
				domain.ActorAISystem,
				now,
			)
			if err == nil {
				if createErr := s.repo.CreateProposal(ctx, *p); createErr != nil {
					return nil, createErr
				}
				drafted = p
			}
		}
	}

	auditDetails := map[string]any{
		"provider":           result.Provider,
		"model":              result.Model,
		"prompt_tokens":      result.PromptTokens,
		"completion_tokens":  result.CompletionTokens,
		"latency_ms":         result.LatencyMS,
		"degraded":           result.Degraded,
		"injection_detected": result.InjectionDetected,
	}
	if drafted != nil {
		auditDetails["draft_proposal_id"] = drafted.ID
		auditDetails["draft_proposal_status"] = drafted.Status
	}

	_ = s.repo.InsertAudit(ctx, domain.AuditEntry{
		ID:            "aud-" + uuid.NewString(),
		CorrelationID: correlationID,
		Actor:         actor,
		ActorType:     domain.ActorHuman,
		Action:        "INCIDENT_INVESTIGATED",
		ResourceType:  "INCIDENT",
		ResourceID:    inc.ID,
		Outcome:       "SUCCESS",
		Details:       auditDetails,
		CreatedAt:     now,
	})

	return &InvestigationOutput{
		Incident:      *inc,
		Investigation: *result,
		DraftProposal: drafted,
	}, nil
}

func buildDegradedInvestigation(inc domain.CorrelatedIncident, injectionDetected bool, provider, reason string) *domain.AIInvestigationResult {
	recAction := string(domain.ProposalTriggerEmergencyReorder)
	recTarget := inc.RootSupplierID
	rationale := "Deterministic fallback analysis generated from correlated risk telemetry."

	if len(inc.Recommendations) > 0 {
		top := inc.Recommendations[0]
		if top.TargetType == "ROUTE" && len(inc.AffectedShipmentIDs) > 0 {
			recAction = string(domain.ProposalRerouteShipment)
			recTarget = inc.AffectedShipmentIDs[0]
			rationale = top.Rationale
		} else if top.TargetType == "SUPPLIER" && len(inc.AffectedSKUs) > 0 {
			recAction = string(domain.ProposalShiftSupplierAllocation)
			recTarget = inc.AffectedSKUs[0]
			rationale = top.Rationale
		}
	} else if len(inc.AffectedSKUs) > 0 {
		recAction = string(domain.ProposalTriggerEmergencyReorder)
		recTarget = inc.AffectedSKUs[0]
	}

	summary := fmt.Sprintf("Deterministic executive summary (AI provider degraded: %s): %s Risk score %.1f (%s).",
		provider, inc.Summary, inc.RiskScore, inc.Severity)
	if len(inc.ShortageAlerts) > 0 {
		topAlert := inc.ShortageAlerts[0]
		summary += fmt.Sprintf(" Most urgent SKU is %s with %.1f days of cover (shortfall %d units).",
			topAlert.SKU, topAlert.DaysOfCover, topAlert.ShortfallUnits)
	}

	return &domain.AIInvestigationResult{
		Summary:               summary,
		RootCauseHypothesis:   fmt.Sprintf("Correlated disruption originating from %s impacting %d shipment(s).", inc.RootSupplierID, len(inc.AffectedShipmentIDs)),
		RecommendedActionType: recAction,
		RecommendedTargetID:   recTarget,
		Rationale:             rationale + " (Fallback reason: " + reason + ")",
		Confidence:            0.70,
		Model:                 "deterministic-fallback",
		Provider:              provider,
		InjectionDetected:     injectionDetected,
		Degraded:              true,
		GeneratedAt:           time.Now().UTC(),
	}
}

func deriveSafeProposalSpec(inc domain.CorrelatedIncident, res *domain.AIInvestigationResult) (domain.ProposalType, string, map[string]any) {
	params := map[string]any{}

	// Prefer concrete entity targets from verified deterministic correlation data (never arbitrary untrusted IDs).
	proposedType := domain.ProposalType(res.RecommendedActionType)
	switch proposedType {
	case domain.ProposalRerouteShipment:
		if len(inc.AffectedShipmentIDs) > 0 {
			target := inc.AffectedShipmentIDs[0]
			for _, rec := range inc.Recommendations {
				if rec.TargetType == "ROUTE" {
					params["new_route_id"] = rec.AlternativeID
					break
				}
			}
			return proposedType, target, params
		}
	case domain.ProposalShiftSupplierAllocation:
		if len(inc.AffectedSKUs) > 0 {
			target := inc.AffectedSKUs[0]
			for _, rec := range inc.Recommendations {
				if rec.TargetType == "SUPPLIER" {
					params["new_supplier_id"] = rec.AlternativeID
					break
				}
			}
			return proposedType, target, params
		}
	case domain.ProposalTriggerEmergencyReorder:
		if len(inc.ShortageAlerts) > 0 {
			params["quantity"] = maxIntLocal(inc.ShortageAlerts[0].ShortfallUnits, 250)
			return proposedType, inc.ShortageAlerts[0].SKU, params
		}
		if len(inc.AffectedSKUs) > 0 {
			params["quantity"] = 500
			return proposedType, inc.AffectedSKUs[0], params
		}
	}

	// Safe deterministic fallback if AI suggested an inapplicable target/action.
	if len(inc.AffectedShipmentIDs) > 0 {
		for _, rec := range inc.Recommendations {
			if rec.TargetType == "ROUTE" {
				params["new_route_id"] = rec.AlternativeID
				return domain.ProposalRerouteShipment, inc.AffectedShipmentIDs[0], params
			}
		}
	}
	if len(inc.ShortageAlerts) > 0 {
		params["quantity"] = maxIntLocal(inc.ShortageAlerts[0].ShortfallUnits, 250)
		return domain.ProposalTriggerEmergencyReorder, inc.ShortageAlerts[0].SKU, params
	}
	if len(inc.AffectedSKUs) > 0 {
		params["quantity"] = 500
		return domain.ProposalTriggerEmergencyReorder, inc.AffectedSKUs[0], params
	}
	return "", "", nil
}

func maxIntLocal(a, b int) int {
	if a > b {
		return a
	}
	return b
}
