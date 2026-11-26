package domain

import (
	"errors"
	"testing"
	"time"
)

func TestChangeProposal_HumanOnlyApprovalAndExecutionInvariant(t *testing.T) {
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)

	p, err := NewChangeProposal(
		"prop-1",
		"inc-sup-1",
		ProposalRerouteShipment,
		"shp-101",
		map[string]any{"new_route_id": "rt-air"},
		"Reroute shp-101 via Taipei-Frankfurt Air",
		"Mitigates 8-day port delay",
		"ai-investigator",
		ActorAISystem,
		now,
	)
	if err != nil {
		t.Fatalf("unexpected error creating proposal: %v", err)
	}
	if p.Status != ProposalPendingApproval {
		t.Fatalf("expected initial status PENDING_APPROVAL, got %s", p.Status)
	}

	// 1. Executing a PENDING_APPROVAL proposal must fail with ErrApprovalRequired.
	if err := p.MarkExecuted("alice@ops", ActorHuman, now); !errors.Is(err, ErrApprovalRequired) {
		t.Fatalf("expected ErrApprovalRequired on unapproved execution, got %v", err)
	}

	// 2. AI_SYSTEM attempting to approve must fail with ErrAutonomousExecutionForbidden.
	if err := p.Approve("ai-investigator", ActorAISystem, "auto-approve", now); !errors.Is(err, ErrAutonomousExecutionForbidden) {
		t.Fatalf("expected ErrAutonomousExecutionForbidden when AI attempts approval, got %v", err)
	}

	// 3. SYSTEM attempting to approve must also fail with ErrAutonomousExecutionForbidden.
	if err := p.Approve("cron", ActorSystem, "auto", now); !errors.Is(err, ErrAutonomousExecutionForbidden) {
		t.Fatalf("expected ErrAutonomousExecutionForbidden when SYSTEM attempts approval, got %v", err)
	}

	// 4. Empty human identity cannot approve.
	if err := p.Approve("   ", ActorHuman, "ok", now); !errors.Is(err, ErrApprovalRequired) {
		t.Fatalf("expected ErrApprovalRequired for empty human approver, got %v", err)
	}

	// 5. Valid human approval succeeds.
	if err := p.Approve("maria.approver@corp.io", ActorHuman, "Approved expedited air freight", now.Add(time.Minute)); err != nil {
		t.Fatalf("expected human approval to succeed, got %v", err)
	}
	if p.Status != ProposalApproved || p.ApprovedBy != "maria.approver@corp.io" || p.ApprovedByType != ActorHuman {
		t.Fatalf("unexpected proposal state after approval: %+v", p)
	}

	// 6. AI_SYSTEM still cannot execute even after human approval!
	if err := p.MarkExecuted("ai-investigator", ActorAISystem, now.Add(2*time.Minute)); !errors.Is(err, ErrAutonomousExecutionForbidden) {
		t.Fatalf("expected ErrAutonomousExecutionForbidden when AI attempts execution, got %v", err)
	}

	// 7. Human operator executes approved proposal successfully.
	if err := p.MarkExecuted("ops.executor@corp.io", ActorHuman, now.Add(3*time.Minute)); err != nil {
		t.Fatalf("expected execution to succeed, got %v", err)
	}
	if p.Status != ProposalExecuted || p.ExecutedAt == nil {
		t.Fatalf("expected EXECUTED status with timestamp, got %+v", p)
	}

	// 8. Re-executing already executed proposal fails with ErrInvalidTransition.
	if err := p.MarkExecuted("ops.executor@corp.io", ActorHuman, now.Add(4*time.Minute)); !errors.Is(err, ErrInvalidTransition) {
		t.Fatalf("expected ErrInvalidTransition on double execution, got %v", err)
	}
}

func TestChangeProposal_RejectedCannotExecute(t *testing.T) {
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)

	p, err := NewChangeProposal(
		"prop-2",
		"inc-sup-1",
		ProposalTriggerEmergencyReorder,
		"SKU-MCU-01",
		map[string]any{"quantity": 500},
		"Emergency reorder 500 units",
		"Low safety stock",
		"operator",
		ActorHuman,
		now,
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if err := p.Reject("ai-bot", ActorAISystem, "nope", now); !errors.Is(err, ErrAutonomousExecutionForbidden) {
		t.Fatalf("expected ErrAutonomousExecutionForbidden on AI reject, got %v", err)
	}
	if err := p.Reject("maria.approver@corp.io", ActorHuman, "Cost too high", now); err != nil {
		t.Fatalf("expected human reject to succeed, got %v", err)
	}
	if p.Status != ProposalRejected {
		t.Fatalf("expected REJECTED, got %s", p.Status)
	}
	if err := p.MarkExecuted("ops@corp.io", ActorHuman, now); !errors.Is(err, ErrApprovalRequired) {
		t.Fatalf("expected ErrApprovalRequired when executing rejected proposal, got %v", err)
	}
}
