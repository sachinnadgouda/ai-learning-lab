package application

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

// GovernanceService manages the human-in-the-loop ChangeProposal lifecycle and persistent audit trail.
type GovernanceService struct {
	repo      Repository
	processor *Processor
}

func NewGovernanceService(repo Repository, processor *Processor) *GovernanceService {
	return &GovernanceService{
		repo:      repo,
		processor: processor,
	}
}

func (g *GovernanceService) CreateProposal(
	ctx context.Context,
	incidentID string,
	proposalType domain.ProposalType,
	targetEntityID string,
	params map[string]any,
	summary string,
	rationale string,
	actor string,
	actorType domain.ActorType,
	correlationID string,
) (*domain.ChangeProposal, error) {
	now := time.Now().UTC()
	p, err := domain.NewChangeProposal(
		"prop-"+uuid.NewString(),
		incidentID,
		proposalType,
		targetEntityID,
		params,
		summary,
		rationale,
		actor,
		actorType,
		now,
	)
	if err != nil {
		return nil, err
	}
	if err := g.repo.CreateProposal(ctx, *p); err != nil {
		return nil, err
	}
	_ = g.repo.InsertAudit(ctx, domain.AuditEntry{
		ID:            "aud-" + uuid.NewString(),
		CorrelationID: correlationID,
		Actor:         actor,
		ActorType:     actorType,
		Action:        "PROPOSAL_CREATED",
		ResourceType:  "CHANGE_PROPOSAL",
		ResourceID:    p.ID,
		Outcome:       "SUCCESS",
		Details:       map[string]any{"proposal_type": p.ProposalType, "target": p.TargetEntityID, "status": p.Status},
		CreatedAt:     now,
	})
	return p, nil
}

func (g *GovernanceService) ApproveProposal(
	ctx context.Context,
	proposalID string,
	actor string,
	actorType domain.ActorType,
	comment string,
	correlationID string,
) (*domain.ChangeProposal, error) {
	p, err := g.repo.GetProposal(ctx, proposalID)
	if err != nil {
		return nil, err
	}
	now := time.Now().UTC()
	if err := p.Approve(actor, actorType, comment, now); err != nil {
		_ = g.repo.InsertAudit(ctx, domain.AuditEntry{
			ID:            "aud-" + uuid.NewString(),
			CorrelationID: correlationID,
			Actor:         actor,
			ActorType:     actorType,
			Action:        "PROPOSAL_APPROVE_DENIED",
			ResourceType:  "CHANGE_PROPOSAL",
			ResourceID:    p.ID,
			Outcome:       "DENIED",
			Details:       map[string]any{"reason": err.Error()},
			CreatedAt:     now,
		})
		return nil, err
	}
	if err := g.repo.UpdateProposal(ctx, *p); err != nil {
		return nil, err
	}
	_ = g.repo.InsertAudit(ctx, domain.AuditEntry{
		ID:            "aud-" + uuid.NewString(),
		CorrelationID: correlationID,
		Actor:         actor,
		ActorType:     actorType,
		Action:        "PROPOSAL_APPROVED",
		ResourceType:  "CHANGE_PROPOSAL",
		ResourceID:    p.ID,
		Outcome:       "SUCCESS",
		Details:       map[string]any{"comment": comment},
		CreatedAt:     now,
	})
	return p, nil
}

func (g *GovernanceService) RejectProposal(
	ctx context.Context,
	proposalID string,
	actor string,
	actorType domain.ActorType,
	reason string,
	correlationID string,
) (*domain.ChangeProposal, error) {
	p, err := g.repo.GetProposal(ctx, proposalID)
	if err != nil {
		return nil, err
	}
	now := time.Now().UTC()
	if err := p.Reject(actor, actorType, reason, now); err != nil {
		_ = g.repo.InsertAudit(ctx, domain.AuditEntry{
			ID:            "aud-" + uuid.NewString(),
			CorrelationID: correlationID,
			Actor:         actor,
			ActorType:     actorType,
			Action:        "PROPOSAL_REJECT_DENIED",
			ResourceType:  "CHANGE_PROPOSAL",
			ResourceID:    p.ID,
			Outcome:       "DENIED",
			Details:       map[string]any{"reason": err.Error()},
			CreatedAt:     now,
		})
		return nil, err
	}
	if err := g.repo.UpdateProposal(ctx, *p); err != nil {
		return nil, err
	}
	_ = g.repo.InsertAudit(ctx, domain.AuditEntry{
		ID:            "aud-" + uuid.NewString(),
		CorrelationID: correlationID,
		Actor:         actor,
		ActorType:     actorType,
		Action:        "PROPOSAL_REJECTED",
		ResourceType:  "CHANGE_PROPOSAL",
		ResourceID:    p.ID,
		Outcome:       "SUCCESS",
		Details:       map[string]any{"reason": reason},
		CreatedAt:     now,
	})
	return p, nil
}

// ExecuteProposal enforces the strict human-approval domain invariant BEFORE applying any supply-chain mutation.
func (g *GovernanceService) ExecuteProposal(
	ctx context.Context,
	proposalID string,
	actor string,
	actorType domain.ActorType,
	correlationID string,
) (*domain.ChangeProposal, error) {
	p, err := g.repo.GetProposal(ctx, proposalID)
	if err != nil {
		return nil, err
	}
	now := time.Now().UTC()
	if err := p.MarkExecuted(actor, actorType, now); err != nil {
		_ = g.repo.InsertAudit(ctx, domain.AuditEntry{
			ID:            "aud-" + uuid.NewString(),
			CorrelationID: correlationID,
			Actor:         actor,
			ActorType:     actorType,
			Action:        "PROPOSAL_EXECUTE_DENIED",
			ResourceType:  "CHANGE_PROPOSAL",
			ResourceID:    p.ID,
			Outcome:       "DENIED",
			Details:       map[string]any{"reason": err.Error(), "current_status": p.Status},
			CreatedAt:     now,
		})
		return nil, err
	}

	// Apply the approved operational mutation.
	if err := g.applyApprovedMutation(ctx, *p, now); err != nil {
		return nil, err
	}

	if err := g.repo.UpdateProposal(ctx, *p); err != nil {
		return nil, err
	}

	if p.IncidentID != "" {
		if inc, err := g.repo.GetIncident(ctx, p.IncidentID); err == nil {
			inc.Status = domain.IncidentMitigated
			inc.UpdatedAt = now
			_ = g.repo.UpsertIncident(ctx, *inc)
		}
	}

	if g.processor != nil {
		_ = g.processor.RecomputeIncidents(ctx)
	}

	_ = g.repo.InsertAudit(ctx, domain.AuditEntry{
		ID:            "aud-" + uuid.NewString(),
		CorrelationID: correlationID,
		Actor:         actor,
		ActorType:     actorType,
		Action:        "PROPOSAL_EXECUTED",
		ResourceType:  "CHANGE_PROPOSAL",
		ResourceID:    p.ID,
		Outcome:       "SUCCESS",
		Details: map[string]any{
			"proposal_type": p.ProposalType,
			"target":        p.TargetEntityID,
			"approved_by":   p.ApprovedBy,
		},
		CreatedAt: now,
	})
	return p, nil
}

func (g *GovernanceService) applyApprovedMutation(ctx context.Context, p domain.ChangeProposal, now time.Time) error {
	switch p.ProposalType {
	case domain.ProposalRerouteShipment:
		sh, err := g.repo.GetShipment(ctx, p.TargetEntityID)
		if err != nil {
			return err
		}
		newRouteID, _ := p.Parameters["new_route_id"].(string)
		if newRouteID == "" {
			return fmt.Errorf("%w: parameters.new_route_id is required for REROUTE_SHIPMENT", domain.ErrInvalidInput)
		}
		rt, err := g.repo.GetRoute(ctx, newRouteID)
		if err != nil {
			return err
		}
		sh.RouteID = rt.ID
		sh.Status = domain.ShipmentRerouted
		sh.DelayHours = 0
		sh.CurrentETA = now.Add(time.Duration(rt.BaseTransitDays * float64(24*time.Hour)))
		sh.Notes = fmt.Sprintf("Rerouted to %s via approved proposal %s", rt.Name, p.ID)
		sh.UpdatedAt = now
		return g.repo.UpsertShipment(ctx, *sh)

	case domain.ProposalShiftSupplierAllocation:
		item, err := g.repo.GetInventoryItem(ctx, p.TargetEntityID)
		if err != nil {
			return err
		}
		newSupID, _ := p.Parameters["new_supplier_id"].(string)
		if newSupID == "" {
			return fmt.Errorf("%w: parameters.new_supplier_id is required for SHIFT_SUPPLIER_ALLOCATION", domain.ErrInvalidInput)
		}
		sup, err := g.repo.GetSupplier(ctx, newSupID)
		if err != nil {
			return err
		}
		item.PrimarySupplierID = sup.ID
		item.LeadTimeDays = sup.LeadTimeDays
		item.UpdatedAt = now
		return g.repo.UpsertInventoryItem(ctx, *item)

	case domain.ProposalTriggerEmergencyReorder:
		item, err := g.repo.GetInventoryItem(ctx, p.TargetEntityID)
		if err != nil {
			return err
		}
		qty := int(floatFromMap(p.Parameters, "quantity", 500))
		if qty <= 0 {
			qty = 500
		}
		supID, _ := p.Parameters["supplier_id"].(string)
		if supID == "" {
			supID = item.PrimarySupplierID
		}
		routeID, _ := p.Parameters["route_id"].(string)
		if routeID == "" {
			routeID = "RT-AIR-TW-FRA"
		}
		eta := now.Add(72 * time.Hour)
		expedited := domain.Shipment{
			ID:           "SHP-EMERG-" + uuid.NewString()[:8],
			SupplierID:   supID,
			RouteID:      routeID,
			SKU:          item.SKU,
			Quantity:     qty,
			Status:       domain.ShipmentInTransit,
			ScheduledETA: eta,
			CurrentETA:   eta,
			DelayHours:   0,
			Notes:        fmt.Sprintf("Emergency reorder triggered by approved proposal %s", p.ID),
			UpdatedAt:    now,
		}
		return g.repo.UpsertShipment(ctx, expedited)

	default:
		return fmt.Errorf("%w: unsupported proposal type %q", domain.ErrInvalidInput, p.ProposalType)
	}
}
