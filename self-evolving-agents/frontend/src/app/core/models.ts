export type PolicyMode = 'STATIC' | 'EVOLVED' | string;
export type DecisionType = 'PROPOSE' | 'NO_CHANGE' | 'INSUFFICIENT_DATA';

export interface TrafficPolicy {
  version: number;
  mode: PolicyMode;
  admissionPerSecond: number;
  maxQueue: number;
  simulatedWorkMs: number;
}

export interface PolicyCandidate {
  policy: TrafficPolicy;
  baselineVersion: number;
  source: string;
  rationale: string;
  cycleId: string | null;
  createdAt: string;
}

export interface TrafficMetrics {
  observedAt: string;
  windowSeconds: number;
  incomingRps: number;
  admittedRps: number;
  rejectedRps: number;
  backendThroughputRps: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  p99LatencyMs: number;
  backend5xxRate: number;
  backendConcurrency: number;
  queueDepth: number;
  queueP95WaitSeconds: number;
  source: string;
}

export interface TrafficPoint {
  time: string;
  incoming: number;
  admitted: number;
  rejected: number;
  backendOk: number;
  backend5xx: number;
  p95LatencyMs: number;
  queueDepth: number;
}

export interface Timeseries {
  seconds: number;
  stepSeconds: number;
  source: string;
  points: TrafficPoint[];
}

export interface FeatureFlags {
  aiEnabled: boolean;
  aiSource: string;
  automaticEvolutionEnabled: boolean;
  automaticSource: string;
  model: string;
  modelSource: string;
  triggerArrivals: number;
  triggerSource: string;
}

export interface AiModelStatus {
  reachable: boolean;
  baseUrl: string;
  installedModels: string[];
  selectedModelInstalled: boolean;
  detail: string;
}

export interface DashboardSummary {
  metrics: TrafficMetrics;
  trafficMode: string;
  activePolicy: TrafficPolicy;
  candidatePolicy: TrafficPolicy | null;
  candidateEvaluated: boolean;
  flags: FeatureFlags;
  ollama: AiModelStatus;
}

export interface EvaluationOutcome {
  arrivals: number;
  admitted: number;
  rejected: number;
  queuedAtEnd: number;
  cumulativeWaitSeconds: number;
  maxQueueDepth: number;
  capacityOverflow: number;
  estimatedP95BackendMs: number;
  estimatedErrorRate: number;
  estimatedP95WaitSeconds: number;
}

export interface EvaluationResult {
  baselineVersion: number;
  candidateVersion: number;
  candidateFingerprint: string;
  baseline: EvaluationOutcome;
  candidate: EvaluationOutcome;
  valid: boolean;
  reasons: string[];
  methodology: string;
  traceSource: string;
  backendCapacity: number;
  evaluatedAt: string;
}

export interface PolicyState {
  active: TrafficPolicy;
  candidate: PolicyCandidate | null;
  evaluation: EvaluationResult | null;
  approvable: boolean;
  history: TrafficPolicy[];
}

export interface CycleResult {
  cycleId: string;
  status: string;
  decision: DecisionType | null;
  rationale: string;
  candidate: TrafficPolicy | null;
  evaluation: EvaluationResult | null;
  aiInvocationId: string | null;
  awaitingApproval: boolean;
  note: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface AiInvocationSummary {
  id: string;
  cycleId: string | null;
  createdAt: string;
  model: string;
  decision: DecisionType | null;
  proposedAdmission: number | null;
  proposedMaxQueue: number | null;
  validationStatus: string;
  status: string;
  outcome: string | null;
  durationMs: number;
  totalTokens: number | null;
}

export interface AiInvocationDetail {
  id: string;
  cycleId: string | null;
  createdAt: string;
  model: { name: string; version: string | null };
  prompt: {
    system: string;
    user: string;
    requestPayload: string;
    parameters: string;
    metricsContext: string;
    historyContext: string;
  };
  rawResponse: string | null;
  aiRecommendation: {
    decision: DecisionType | null;
    admissionPerSecond: number | null;
    maxQueue: number | null;
    rationale: string | null;
  };
  independentValidation: {
    status: string;
    errors: string[];
    evaluation: EvaluationResult | null;
    outcome: string | null;
    candidateVersion: number | null;
  };
  timing: { durationMs: number; promptTokens: number | null; completionTokens: number | null };
  callStatus: string;
  error: string | null;
  redacted: boolean;
  policyVersionBefore: number | null;
}

export interface FieldDiff {
  field: string;
  left: string;
  right: string;
  different: boolean;
}

export interface InvocationComparison {
  left: AiInvocationDetail;
  right: AiInvocationDetail;
  differences: FieldDiff[];
}

export interface EvolutionExperiment {
  cycleId: string;
  startedAt: string;
  trigger: string;
  status: string;
  baseline: TrafficPolicy;
  candidate: TrafficPolicy | null;
  decision: DecisionType | null;
  rationale: string | null;
  evaluation: EvaluationResult | null;
  aiInvocationId: string | null;
  observedArrivals: number;
  outcomeNote: string | null;
}

export interface EvolutionEvent {
  time: string;
  level: string;
  component: string;
  stage: string;
  cycleId: string | null;
  aiInvocationId: string | null;
  policyVersion: number | null;
  message: string;
  data: Record<string, unknown>;
}

export interface SystemHealth {
  status: string;
  redis: string;
  postgres: string;
  ollama: string;
}

export interface AiStatus {
  flags: FeatureFlags;
  ollama: AiModelStatus;
  approvalRequired: boolean;
  mode: string;
}

export type RuntimeSettings = Record<string, unknown>;

export interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
  code?: string;
  requestId?: string;
}

export interface InvocationFilter {
  page?: number;
  size?: number;
  decision?: string;
  status?: string;
  validation?: string;
  cycleId?: string;
  model?: string;
  q?: string;
}

export interface EventFilterParams {
  page?: number;
  size?: number;
  from?: string;
  level?: string;
  cycleId?: string;
  aiInvocationId?: string;
  policyVersion?: number | null;
  component?: string;
  q?: string;
}

export function fingerprintOf(p: TrafficPolicy): string {
  return `${p.version}:${p.mode}:${p.admissionPerSecond}:${p.maxQueue}:${p.simulatedWorkMs}`;
}
