package domain

import (
	"errors"
	"time"
)

// Domain sentinel errors.
var (
	ErrNotFound                     = errors.New("resource not found")
	ErrInvalidInput                 = errors.New("invalid input")
	ErrQueueFull                    = errors.New("event intake queue is full")
	ErrDuplicateEvent               = errors.New("duplicate event already processed")
	ErrApprovalRequired             = errors.New("explicit human approval is required before execution")
	ErrAutonomousExecutionForbidden = errors.New("autonomous AI approval or execution is strictly forbidden")
	ErrInvalidTransition            = errors.New("invalid state transition")
	ErrUpstreamUnavailable          = errors.New("upstream dependency unavailable")
	ErrRateLimited                  = errors.New("rate limit exceeded")
	ErrUnauthorized                 = errors.New("authentication required")
	ErrForbidden                    = errors.New("insufficient permissions")
)

type EventType string

const (
	EventShipmentDelayed      EventType = "SHIPMENT_DELAYED"
	EventSupplierDisruption   EventType = "SUPPLIER_DISRUPTION"
	EventRouteCongestion      EventType = "ROUTE_CONGESTION"
	EventInventoryConsumed    EventType = "INVENTORY_CONSUMED"
	EventInventoryReplenished EventType = "INVENTORY_REPLENISHED"
	EventSupplierRecovered    EventType = "SUPPLIER_RECOVERED"
)

func (e EventType) Valid() bool {
	switch e {
	case EventShipmentDelayed,
		EventSupplierDisruption,
		EventRouteCongestion,
		EventInventoryConsumed,
		EventInventoryReplenished,
		EventSupplierRecovered:
		return true
	default:
		return false
	}
}

type Severity string

const (
	SeverityLow      Severity = "LOW"
	SeverityMedium   Severity = "MEDIUM"
	SeverityHigh     Severity = "HIGH"
	SeverityCritical Severity = "CRITICAL"
)

func SeverityFromScore(score float64) Severity {
	switch {
	case score >= 75:
		return SeverityCritical
	case score >= 55:
		return SeverityHigh
	case score >= 30:
		return SeverityMedium
	default:
		return SeverityLow
	}
}

func SeverityRank(s Severity) int {
	switch s {
	case SeverityCritical:
		return 4
	case SeverityHigh:
		return 3
	case SeverityMedium:
		return 2
	default:
		return 1
	}
}

type SupplierStatus string

const (
	SupplierOperational SupplierStatus = "OPERATIONAL"
	SupplierDegraded    SupplierStatus = "DEGRADED"
	SupplierDisrupted   SupplierStatus = "DISRUPTED"
)

type RouteStatus string

const (
	RouteClear     RouteStatus = "CLEAR"
	RouteCongested RouteStatus = "CONGESTED"
	RouteBlocked   RouteStatus = "BLOCKED"
)

type ShipmentStatus string

const (
	ShipmentScheduled ShipmentStatus = "SCHEDULED"
	ShipmentInTransit ShipmentStatus = "IN_TRANSIT"
	ShipmentDelayed   ShipmentStatus = "DELAYED"
	ShipmentDelivered ShipmentStatus = "DELIVERED"
	ShipmentRerouted  ShipmentStatus = "REROUTED"
)

type IncidentStatus string

const (
	IncidentOpen          IncidentStatus = "OPEN"
	IncidentInvestigating IncidentStatus = "INVESTIGATING"
	IncidentMitigated     IncidentStatus = "MITIGATED"
	IncidentResolved      IncidentStatus = "RESOLVED"
)

type ProposalType string

const (
	ProposalRerouteShipment         ProposalType = "REROUTE_SHIPMENT"
	ProposalShiftSupplierAllocation ProposalType = "SHIFT_SUPPLIER_ALLOCATION"
	ProposalTriggerEmergencyReorder ProposalType = "TRIGGER_EMERGENCY_REORDER"
)

func (p ProposalType) Valid() bool {
	switch p {
	case ProposalRerouteShipment, ProposalShiftSupplierAllocation, ProposalTriggerEmergencyReorder:
		return true
	default:
		return false
	}
}

type ProposalStatus string

const (
	ProposalPendingApproval ProposalStatus = "PENDING_APPROVAL"
	ProposalApproved        ProposalStatus = "APPROVED"
	ProposalRejected        ProposalStatus = "REJECTED"
	ProposalExecuted        ProposalStatus = "EXECUTED"
)

type ActorType string

const (
	ActorHuman    ActorType = "HUMAN"
	ActorAISystem ActorType = "AI_SYSTEM"
	ActorSystem   ActorType = "SYSTEM"
)

type ProcessingStatus string

const (
	ProcessingInProgress ProcessingStatus = "IN_PROGRESS"
	ProcessingProcessed  ProcessingStatus = "PROCESSED"
	ProcessingDuplicate  ProcessingStatus = "DUPLICATE"
	ProcessingOutOfOrder ProcessingStatus = "OUT_OF_ORDER"
	ProcessingDLQ        ProcessingStatus = "DLQ"
)

type Supplier struct {
	ID                       string         `json:"id"`
	Name                     string         `json:"name"`
	Region                   string         `json:"region"`
	Category                 string         `json:"category"`
	Tier                     int            `json:"tier"`
	SingleSource             bool           `json:"single_source"`
	HistoricalDelayRatio     float64        `json:"historical_delay_ratio"`
	ActiveDisruptionSeverity float64        `json:"active_disruption_severity"`
	Status                   SupplierStatus `json:"status"`
	LeadTimeDays             float64        `json:"lead_time_days"`
	UnitCostUSD              float64        `json:"unit_cost_usd"`
	BackupForID              string         `json:"backup_for_id,omitempty"`
	LastOccurredAt           *time.Time     `json:"last_occurred_at,omitempty"`
	UpdatedAt                time.Time      `json:"updated_at"`
}

type Route struct {
	ID                       string      `json:"id"`
	Name                     string      `json:"name"`
	Origin                   string      `json:"origin"`
	Destination              string      `json:"destination"`
	Mode                     string      `json:"mode"`
	BaseTransitDays          float64     `json:"base_transit_days"`
	TransitDelayVarianceDays float64     `json:"transit_delay_variance_days"`
	CongestionSeverity       float64     `json:"congestion_severity"`
	WeatherIncidentCount     int         `json:"weather_incident_count"`
	Status                   RouteStatus `json:"status"`
	CostMultiplier           float64     `json:"cost_multiplier"`
	BackupForID              string      `json:"backup_for_id,omitempty"`
	LastOccurredAt           *time.Time  `json:"last_occurred_at,omitempty"`
	UpdatedAt                time.Time   `json:"updated_at"`
}

type Shipment struct {
	ID             string         `json:"id"`
	SupplierID     string         `json:"supplier_id"`
	RouteID        string         `json:"route_id"`
	SKU            string         `json:"sku"`
	Quantity       int            `json:"quantity"`
	Status         ShipmentStatus `json:"status"`
	ScheduledETA   time.Time      `json:"scheduled_eta"`
	CurrentETA     time.Time      `json:"current_eta"`
	DelayHours     float64        `json:"delay_hours"`
	Notes          string         `json:"notes,omitempty"`
	LastOccurredAt *time.Time     `json:"last_occurred_at,omitempty"`
	UpdatedAt      time.Time      `json:"updated_at"`
}

type InventoryItem struct {
	SKU               string     `json:"sku"`
	Name              string     `json:"name"`
	Warehouse         string     `json:"warehouse"`
	PrimarySupplierID string     `json:"primary_supplier_id"`
	OnHandUnits       int        `json:"on_hand_units"`
	DailyBurnRate     float64    `json:"daily_burn_rate"`
	SafetyStockDays   float64    `json:"safety_stock_days"`
	ReorderPointUnits int        `json:"reorder_point_units"`
	LeadTimeDays      float64    `json:"lead_time_days"`
	LastOccurredAt    *time.Time `json:"last_occurred_at,omitempty"`
	UpdatedAt         time.Time  `json:"updated_at"`
}

type SupplyEvent struct {
	EventID          string           `json:"event_id"`
	EventType        EventType        `json:"event_type"`
	EntityID         string           `json:"entity_id"`
	EntityType       string           `json:"entity_type,omitempty"`
	OccurredAt       time.Time        `json:"occurred_at"`
	ReceivedAt       time.Time        `json:"received_at"`
	Payload          map[string]any   `json:"payload,omitempty"`
	Notes            string           `json:"notes,omitempty"`
	Version          int              `json:"version"`
	CorrelationID    string           `json:"correlation_id,omitempty"`
	ProcessingStatus ProcessingStatus `json:"processing_status,omitempty"`
	OutOfOrder       bool             `json:"out_of_order,omitempty"`
}

type RiskFactor struct {
	Name         string  `json:"name"`
	Contribution float64 `json:"contribution"`
	Detail       string  `json:"detail"`
}

type RiskScore struct {
	EntityID   string       `json:"entity_id"`
	EntityType string       `json:"entity_type"`
	EntityName string       `json:"entity_name"`
	Score      float64      `json:"score"`
	Severity   Severity     `json:"severity"`
	Factors    []RiskFactor `json:"factors"`
	ComputedAt time.Time    `json:"computed_at"`
}

type ShortageAlert struct {
	SKU                   string     `json:"sku"`
	Name                  string     `json:"name"`
	Warehouse             string     `json:"warehouse"`
	PrimarySupplierID     string     `json:"primary_supplier_id"`
	OnHandUnits           int        `json:"on_hand_units"`
	DailyBurnRate         float64    `json:"daily_burn_rate"`
	DaysOfCover           float64    `json:"days_of_cover"`
	SafetyStockDays       float64    `json:"safety_stock_days"`
	DelayedInboundUnits   int        `json:"delayed_inbound_units"`
	EarliestInboundETA    *time.Time `json:"earliest_inbound_eta,omitempty"`
	ProjectedStockoutDate *time.Time `json:"projected_stockout_date,omitempty"`
	ShortfallUnits        int        `json:"shortfall_units"`
	Severity              Severity   `json:"severity"`
	Explanation           string     `json:"explanation"`
	ComputedAt            time.Time  `json:"computed_at"`
}

type TimelineEvent struct {
	EventID    string    `json:"event_id"`
	EventType  EventType `json:"event_type"`
	EntityID   string    `json:"entity_id"`
	EntityType string    `json:"entity_type"`
	OccurredAt time.Time `json:"occurred_at"`
	ReceivedAt time.Time `json:"received_at"`
	OutOfOrder bool      `json:"out_of_order"`
	Summary    string    `json:"summary"`
	Notes      string    `json:"notes,omitempty"`
}

type AlternativeRecommendation struct {
	TargetType        string  `json:"target_type"`
	CurrentID         string  `json:"current_id"`
	AlternativeID     string  `json:"alternative_id"`
	AlternativeName   string  `json:"alternative_name"`
	LeadTimeDeltaDays float64 `json:"lead_time_delta_days"`
	CostDeltaPct      float64 `json:"cost_delta_pct"`
	RiskScoreDelta    float64 `json:"risk_score_delta"`
	Rationale         string  `json:"rationale"`
	RankScore         float64 `json:"rank_score"`
}

type AIInvestigationResult struct {
	Summary               string    `json:"summary"`
	RootCauseHypothesis   string    `json:"root_cause_hypothesis"`
	RecommendedActionType string    `json:"recommended_action_type"`
	RecommendedTargetID   string    `json:"recommended_target_id"`
	Rationale             string    `json:"rationale"`
	Confidence            float64   `json:"confidence"`
	Model                 string    `json:"model"`
	Provider              string    `json:"provider"`
	PromptTokens          int       `json:"prompt_tokens"`
	CompletionTokens      int       `json:"completion_tokens"`
	LatencyMS             int64     `json:"latency_ms"`
	InjectionDetected     bool      `json:"injection_detected"`
	Degraded              bool      `json:"degraded"`
	GeneratedAt           time.Time `json:"generated_at"`
}

type CorrelatedIncident struct {
	ID                  string                      `json:"id"`
	Title               string                      `json:"title"`
	Status              IncidentStatus              `json:"status"`
	Severity            Severity                    `json:"severity"`
	RootSupplierID      string                      `json:"root_supplier_id,omitempty"`
	AffectedRouteIDs    []string                    `json:"affected_route_ids"`
	AffectedShipmentIDs []string                    `json:"affected_shipment_ids"`
	AffectedSKUs        []string                    `json:"affected_skus"`
	RiskScore           float64                     `json:"risk_score"`
	Summary             string                      `json:"summary"`
	Timeline            []TimelineEvent             `json:"timeline"`
	ShortageAlerts      []ShortageAlert             `json:"shortage_alerts"`
	Recommendations     []AlternativeRecommendation `json:"recommendations"`
	AIInvestigation     *AIInvestigationResult      `json:"ai_investigation,omitempty"`
	CreatedAt           time.Time                   `json:"created_at"`
	UpdatedAt           time.Time                   `json:"updated_at"`
}

type ChangeProposal struct {
	ID              string         `json:"id"`
	IncidentID      string         `json:"incident_id"`
	ProposalType    ProposalType   `json:"proposal_type"`
	TargetEntityID  string         `json:"target_entity_id"`
	Parameters      map[string]any `json:"parameters,omitempty"`
	Summary         string         `json:"summary"`
	Rationale       string         `json:"rationale"`
	ProposedBy      string         `json:"proposed_by"`
	ProposedByType  ActorType      `json:"proposed_by_type"`
	Status          ProposalStatus `json:"status"`
	ApprovedBy      string         `json:"approved_by,omitempty"`
	ApprovedByType  ActorType      `json:"approved_by_type,omitempty"`
	ApprovalComment string         `json:"approval_comment,omitempty"`
	RejectedBy      string         `json:"rejected_by,omitempty"`
	RejectedReason  string         `json:"rejected_reason,omitempty"`
	ExecutedBy      string         `json:"executed_by,omitempty"`
	ExecutedAt      *time.Time     `json:"executed_at,omitempty"`
	CreatedAt       time.Time      `json:"created_at"`
	UpdatedAt       time.Time      `json:"updated_at"`
}

type DeadLetterEvent struct {
	ID          string     `json:"id"`
	EventID     string     `json:"event_id"`
	EventType   EventType  `json:"event_type"`
	EntityID    string     `json:"entity_id"`
	RawPayload  string     `json:"raw_payload"`
	ErrorReason string     `json:"error_reason"`
	RetryCount  int        `json:"retry_count"`
	Replayed    bool       `json:"replayed"`
	ReplayedAt  *time.Time `json:"replayed_at,omitempty"`
	CreatedAt   time.Time  `json:"created_at"`
}

type AlertNotification struct {
	ID         string    `json:"id"`
	IncidentID string    `json:"incident_id"`
	Severity   Severity  `json:"severity"`
	Title      string    `json:"title"`
	Message    string    `json:"message"`
	Channel    string    `json:"channel"`
	Delivered  bool      `json:"delivered"`
	Error      string    `json:"error,omitempty"`
	CreatedAt  time.Time `json:"created_at"`
}

type AuditEntry struct {
	ID            string         `json:"id"`
	CorrelationID string         `json:"correlation_id"`
	Actor         string         `json:"actor"`
	ActorType     ActorType      `json:"actor_type"`
	Action        string         `json:"action"`
	ResourceType  string         `json:"resource_type"`
	ResourceID    string         `json:"resource_id"`
	Outcome       string         `json:"outcome"`
	Details       map[string]any `json:"details,omitempty"`
	CreatedAt     time.Time      `json:"created_at"`
}
