package application

import (
	"context"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

// EventApplyTx defines the transactional repository subset exposed inside ProcessEventAtomic.
// All reads and writes performed through this interface execute on a single SQL transaction.
type EventApplyTx interface {
	GetSupplier(ctx context.Context, id string) (*domain.Supplier, error)
	UpsertSupplier(ctx context.Context, s domain.Supplier) error

	GetRoute(ctx context.Context, id string) (*domain.Route, error)
	UpsertRoute(ctx context.Context, r domain.Route) error

	GetShipment(ctx context.Context, id string) (*domain.Shipment, error)
	UpsertShipment(ctx context.Context, sh domain.Shipment) error

	GetInventoryItem(ctx context.Context, sku string) (*domain.InventoryItem, error)
	UpsertInventoryItem(ctx context.Context, item domain.InventoryItem) error
}

// AtomicProcessResult captures the committed outcome of an atomic event processing transaction.
type AtomicProcessResult struct {
	Duplicate  bool
	OutOfOrder bool
	Status     domain.ProcessingStatus
}

// Delivery wraps a message received from an EventBroker with explicit post-commit Ack/Nack lifecycle hooks.
// If ParseErr != nil, the broker received a malformed entry whose RawPayload must be routed to DLQ
// instead of being silently discarded.
type Delivery struct {
	MessageID  string
	Event      domain.SupplyEvent
	RawPayload string
	ParseErr   error
	Ack        func(ctx context.Context) error
	Nack       func(ctx context.Context, reason string) error
}

// Repository defines the outbound persistence port.
type Repository interface {
	Ping(ctx context.Context) error

	UpsertSupplier(ctx context.Context, s domain.Supplier) error
	GetSupplier(ctx context.Context, id string) (*domain.Supplier, error)
	ListSuppliers(ctx context.Context) ([]domain.Supplier, error)

	UpsertRoute(ctx context.Context, r domain.Route) error
	GetRoute(ctx context.Context, id string) (*domain.Route, error)
	ListRoutes(ctx context.Context) ([]domain.Route, error)

	UpsertShipment(ctx context.Context, sh domain.Shipment) error
	GetShipment(ctx context.Context, id string) (*domain.Shipment, error)
	ListShipments(ctx context.Context) ([]domain.Shipment, error)

	UpsertInventoryItem(ctx context.Context, item domain.InventoryItem) error
	GetInventoryItem(ctx context.Context, sku string) (*domain.InventoryItem, error)
	ListInventoryItems(ctx context.Context) ([]domain.InventoryItem, error)

	// ProcessEventAtomic executes idempotency check/claim, domain state mutation (via applyFn),
	// supply_events append, and final processed_events claim completion inside a single atomic transaction.
	// If any step or commit fails, the entire transaction rolls back and the event remains recoverable.
	ProcessEventAtomic(
		ctx context.Context,
		ev domain.SupplyEvent,
		applyFn func(ctx context.Context, tx EventApplyTx) (outOfOrder bool, err error),
	) (AtomicProcessResult, error)

	// RecordEventDLQAtomic atomically marks the event claim as DLQ (when event_id is non-empty)
	// and inserts the dead-letter record in the same transaction.
	RecordEventDLQAtomic(ctx context.Context, ev domain.SupplyEvent, dlq domain.DeadLetterEvent) error

	// TryClaimEvent atomically claims an event_id for processing.
	// Returns claimed=true if this worker won the claim; claimed=false if duplicate/already processed.
	TryClaimEvent(ctx context.Context, ev domain.SupplyEvent) (claimed bool, err error)
	CompleteEventClaim(ctx context.Context, eventID string, status domain.ProcessingStatus, outOfOrder bool) error
	ResetDLQEventClaim(ctx context.Context, eventID string) error

	AppendSupplyEvent(ctx context.Context, ev domain.SupplyEvent) error
	ListSupplyEvents(ctx context.Context, limit int) ([]domain.SupplyEvent, error)

	UpsertIncident(ctx context.Context, inc domain.CorrelatedIncident) error
	GetIncident(ctx context.Context, id string) (*domain.CorrelatedIncident, error)
	ListIncidents(ctx context.Context) ([]domain.CorrelatedIncident, error)

	CreateProposal(ctx context.Context, p domain.ChangeProposal) error
	UpdateProposal(ctx context.Context, p domain.ChangeProposal) error
	GetProposal(ctx context.Context, id string) (*domain.ChangeProposal, error)
	ListProposals(ctx context.Context) ([]domain.ChangeProposal, error)

	InsertDLQ(ctx context.Context, dlq domain.DeadLetterEvent) error
	GetDLQ(ctx context.Context, id string) (*domain.DeadLetterEvent, error)
	ListDLQ(ctx context.Context) ([]domain.DeadLetterEvent, error)
	MarkDLQReplayed(ctx context.Context, id string, replayedAt time.Time) error

	InsertAlert(ctx context.Context, alert domain.AlertNotification) error
	ListAlerts(ctx context.Context, limit int) ([]domain.AlertNotification, error)

	InsertAudit(ctx context.Context, entry domain.AuditEntry) error
	ListAudit(ctx context.Context, limit int) ([]domain.AuditEntry, error)
}

// EventBroker defines the pluggable event transport port (in-process bounded channel or Redis Streams).
// Receive returns a Delivery whose Ack MUST only be called after durable completion; StopAccepting
// (or Close) allows graceful shutdown without silently discarding pending/in-flight messages.
type EventBroker interface {
	Publish(ctx context.Context, ev domain.SupplyEvent) error
	Receive(ctx context.Context) (Delivery, error)
	Depth() int
	Capacity() int
	Close() error
	Ping(ctx context.Context) error
	Name() string
}

// InvestigationRequest carries deterministic facts and fenced untrusted notes into an AIInvestigator.
type InvestigationRequest struct {
	Incident         domain.CorrelatedIncident
	FencedNotes      []string
	InjectionFlagged bool
	CorrelationID    string
}

// AIInvestigator defines the pluggable advisory AI port.
type AIInvestigator interface {
	Investigate(ctx context.Context, req InvestigationRequest) (*domain.AIInvestigationResult, error)
	ProviderName() string
}

// Notifier defines the pluggable operational alert sink port.
type Notifier interface {
	Notify(ctx context.Context, alert domain.AlertNotification) error
	Channel() string
}
