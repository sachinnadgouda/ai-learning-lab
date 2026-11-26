package domain

import (
	"fmt"
	"strings"
	"time"
)

// NewChangeProposal constructs a ChangeProposal that ALWAYS starts in PENDING_APPROVAL.
// AI systems or human operators may draft proposals, but proposals are never created pre-approved or executed.
func NewChangeProposal(
	id string,
	incidentID string,
	proposalType ProposalType,
	targetEntityID string,
	parameters map[string]any,
	summary string,
	rationale string,
	proposedBy string,
	proposedByType ActorType,
	now time.Time,
) (*ChangeProposal, error) {
	if strings.TrimSpace(id) == "" {
		return nil, fmt.Errorf("%w: proposal id is required", ErrInvalidInput)
	}
	if !proposalType.Valid() {
		return nil, fmt.Errorf("%w: unsupported proposal type %q", ErrInvalidInput, proposalType)
	}
	if strings.TrimSpace(targetEntityID) == "" {
		return nil, fmt.Errorf("%w: target_entity_id is required", ErrInvalidInput)
	}
	if strings.TrimSpace(summary) == "" {
		return nil, fmt.Errorf("%w: summary is required", ErrInvalidInput)
	}
	if strings.TrimSpace(proposedBy) == "" {
		proposedBy = "unknown"
	}
	if proposedByType == "" {
		proposedByType = ActorSystem
	}

	paramsCopy := make(map[string]any, len(parameters))
	for k, v := range parameters {
		paramsCopy[k] = v
	}

	ts := now.UTC()
	return &ChangeProposal{
		ID:             id,
		IncidentID:     incidentID,
		ProposalType:   proposalType,
		TargetEntityID: targetEntityID,
		Parameters:     paramsCopy,
		Summary:        summary,
		Rationale:      rationale,
		ProposedBy:     proposedBy,
		ProposedByType: proposedByType,
		Status:         ProposalPendingApproval,
		CreatedAt:      ts,
		UpdatedAt:      ts,
	}, nil
}

// Approve transitions a proposal from PENDING_APPROVAL -> APPROVED.
// STRICT INVARIANT: Only authenticated HUMAN actors may approve proposals; AI_SYSTEM is strictly forbidden.
func (p *ChangeProposal) Approve(actor string, actorType ActorType, comment string, now time.Time) error {
	if actorType == ActorAISystem || actorType != ActorHuman {
		return fmt.Errorf("%w: actor %q (%s) cannot approve change proposals", ErrAutonomousExecutionForbidden, actor, actorType)
	}
	if strings.TrimSpace(actor) == "" {
		return fmt.Errorf("%w: authenticated human approver identity is required", ErrApprovalRequired)
	}
	if p.Status != ProposalPendingApproval {
		return fmt.Errorf("%w: cannot approve proposal in status %s", ErrInvalidTransition, p.Status)
	}

	p.Status = ProposalApproved
	p.ApprovedBy = strings.TrimSpace(actor)
	p.ApprovedByType = ActorHuman
	p.ApprovalComment = strings.TrimSpace(comment)
	p.UpdatedAt = now.UTC()
	return nil
}

// Reject transitions a proposal from PENDING_APPROVAL -> REJECTED.
// Only HUMAN actors may reject proposals.
func (p *ChangeProposal) Reject(actor string, actorType ActorType, reason string, now time.Time) error {
	if actorType == ActorAISystem || actorType != ActorHuman {
		return fmt.Errorf("%w: actor %q (%s) cannot reject change proposals", ErrAutonomousExecutionForbidden, actor, actorType)
	}
	if strings.TrimSpace(actor) == "" {
		return fmt.Errorf("%w: authenticated human actor is required", ErrApprovalRequired)
	}
	if p.Status != ProposalPendingApproval {
		return fmt.Errorf("%w: cannot reject proposal in status %s", ErrInvalidTransition, p.Status)
	}

	p.Status = ProposalRejected
	p.RejectedBy = strings.TrimSpace(actor)
	p.RejectedReason = strings.TrimSpace(reason)
	p.UpdatedAt = now.UTC()
	return nil
}

// MarkExecuted transitions a proposal from APPROVED -> EXECUTED.
// STRICT INVARIANT:
// 1. Autonomous AI execution is strictly forbidden (ErrAutonomousExecutionForbidden).
// 2. Proposal MUST be in APPROVED status with a non-empty HUMAN approver (ErrApprovalRequired).
func (p *ChangeProposal) MarkExecuted(executor string, executorType ActorType, now time.Time) error {
	if executorType == ActorAISystem {
		return fmt.Errorf("%w: AI_SYSTEM cannot execute change proposals", ErrAutonomousExecutionForbidden)
	}
	if p.Status == ProposalExecuted {
		return fmt.Errorf("%w: proposal already executed", ErrInvalidTransition)
	}
	if p.Status != ProposalApproved {
		return fmt.Errorf("%w: proposal status is %s (must be APPROVED)", ErrApprovalRequired, p.Status)
	}
	if strings.TrimSpace(p.ApprovedBy) == "" || p.ApprovedByType != ActorHuman {
		return fmt.Errorf("%w: missing verified human approver record", ErrApprovalRequired)
	}
	if strings.TrimSpace(executor) == "" {
		return fmt.Errorf("%w: executor identity is required", ErrInvalidInput)
	}

	ts := now.UTC()
	p.Status = ProposalExecuted
	p.ExecutedBy = strings.TrimSpace(executor)
	p.ExecutedAt = &ts
	p.UpdatedAt = ts
	return nil
}
