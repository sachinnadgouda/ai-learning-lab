import {
  AiInvocationDetail, AiInvocationSummary, DashboardSummary, EvaluationResult, FeatureFlags, PolicyState, TrafficPolicy,
} from '../core/models';

export const ACTIVE: TrafficPolicy = { version: 1, mode: 'STATIC', admissionPerSecond: 20, maxQueue: 500, simulatedWorkMs: 30 };
export const CANDIDATE_POLICY: TrafficPolicy = { version: 2, mode: 'EVOLVED', admissionPerSecond: 40, maxQueue: 800, simulatedWorkMs: 30 };

export const FLAGS: FeatureFlags = {
  aiEnabled: true, aiSource: 'runtime', automaticEvolutionEnabled: false, automaticSource: 'environment',
  model: 'qwen2.5:3b', modelSource: 'environment', triggerArrivals: 120, triggerSource: 'environment',
};

const OUTCOME = {
  arrivals: 100, admitted: 80, rejected: 20, queuedAtEnd: 0, cumulativeWaitSeconds: 0, maxQueueDepth: 0, capacityOverflow: 0,
  estimatedP95BackendMs: 120, estimatedErrorRate: 0.01, estimatedP95WaitSeconds: 0,
};

export const EVALUATION: EvaluationResult = {
  baselineVersion: 1, candidateVersion: 2, candidateFingerprint: '2:EVOLVED:40:800:30',
  baseline: OUTCOME, candidate: { ...OUTCOME, admitted: 100, rejected: 0 }, valid: true, reasons: [],
  methodology: 'Deterministic replay', traceSource: 'OBSERVED_ARRIVALS', backendCapacity: 50, evaluatedAt: '2025-01-01T00:00:00Z',
};

export function policyState(evaluated: boolean): PolicyState {
  return {
    active: ACTIVE,
    candidate: { policy: CANDIDATE_POLICY, baselineVersion: 1, source: 'AI', rationale: 'arrivals exceed limit', cycleId: 'cycle-1', createdAt: '2025-01-01T00:00:00Z' },
    evaluation: evaluated ? EVALUATION : null,
    approvable: evaluated,
    history: [],
  };
}

export const SUMMARY: DashboardSummary = {
  metrics: {
    observedAt: '2025-01-01T00:00:00Z', windowSeconds: 60, incomingRps: 41.5, admittedRps: 20, rejectedRps: 21.5,
    backendThroughputRps: 20, p50LatencyMs: 30, p95LatencyMs: 88, p99LatencyMs: 120, backend5xxRate: 0.02, backendConcurrency: 2,
    queueDepth: 7, queueP95WaitSeconds: 0, source: 'OBSERVED',
  },
  trafficMode: 'STATIC', activePolicy: ACTIVE, candidatePolicy: null, candidateEvaluated: false, flags: FLAGS,
  ollama: { reachable: true, baseUrl: 'http://ollama:11434', installedModels: ['qwen2.5:3b'], selectedModelInstalled: true, detail: 'Connected' },
};

export const INVOCATION_SUMMARY: AiInvocationSummary = {
  id: 'inv-12345678', cycleId: 'cycle-1', createdAt: '2025-01-01T00:00:00Z', model: 'qwen2.5:3b', decision: 'PROPOSE',
  proposedAdmission: 40, proposedMaxQueue: 800, validationStatus: 'VALID', status: 'SUCCESS', outcome: 'EVALUATED', durationMs: 1234, totalTokens: 150,
};

export const INVOCATION: AiInvocationDetail = {
  id: 'inv-12345678', cycleId: 'cycle-1', createdAt: '2025-01-01T00:00:00Z', model: { name: 'qwen2.5:3b', version: 'abcdef1234567890' },
  prompt: {
    system: 'You are a traffic policy advisor.', user: 'Metrics: {"incomingRps":41.5}', requestPayload: '{"model":"qwen2.5:3b"}',
    parameters: '{"temperature":0.2}', metricsContext: '{"incomingRps":41.5}', historyContext: '[]',
  },
  rawResponse: '{"message":{"content":"{\\"decision\\":\\"PROPOSE\\"}"}}',
  aiRecommendation: { decision: 'PROPOSE', admissionPerSecond: 40, maxQueue: 800, rationale: 'Arrivals exceed the limit' },
  independentValidation: { status: 'INVALID', errors: ['admissionPerSecond above bound'], evaluation: null, outcome: 'REJECTED', candidateVersion: null },
  timing: { durationMs: 1234, promptTokens: 100, completionTokens: 50 }, callStatus: 'SUCCESS', error: null, redacted: false, policyVersionBefore: 1,
};
