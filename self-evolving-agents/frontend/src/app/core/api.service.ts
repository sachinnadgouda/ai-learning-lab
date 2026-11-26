import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AiInvocationDetail, AiInvocationSummary, AiStatus, CycleResult, DashboardSummary, EvaluationResult,
  EventFilterParams, EvolutionEvent, EvolutionExperiment, FeatureFlags, InvocationComparison, InvocationFilter,
  PageResponse, PolicyState, RuntimeSettings, SystemHealth, Timeseries, TrafficMetrics, TrafficPolicy,
} from './models';

export const API_BASE = '/api/v1';

function toParams(values: object): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== '') params = params.set(key, String(value));
  }
  return params;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  dashboard(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${API_BASE}/dashboard/summary`);
  }
  metrics(windowSeconds = 60): Observable<TrafficMetrics> {
    return this.http.get<TrafficMetrics>(`${API_BASE}/traffic/metrics`, { params: toParams({ windowSeconds }) });
  }
  timeseries(seconds = 300, stepSeconds = 5): Observable<Timeseries> {
    return this.http.get<Timeseries>(`${API_BASE}/traffic/timeseries`, { params: toParams({ seconds, stepSeconds }) });
  }
  health(): Observable<SystemHealth> {
    return this.http.get<SystemHealth>(`${API_BASE}/system/health`);
  }
  settings(): Observable<RuntimeSettings> {
    return this.http.get<RuntimeSettings>(`${API_BASE}/system/settings`);
  }
  aiStatus(): Observable<AiStatus> {
    return this.http.get<AiStatus>(`${API_BASE}/ai/status`);
  }

  invocations(filter: InvocationFilter): Observable<PageResponse<AiInvocationSummary>> {
    return this.http.get<PageResponse<AiInvocationSummary>>(`${API_BASE}/ai/invocations`, { params: toParams(filter) });
  }
  invocation(id: string): Observable<AiInvocationDetail> {
    return this.http.get<AiInvocationDetail>(`${API_BASE}/ai/invocations/${encodeURIComponent(id)}`);
  }
  compareInvocations(left: string, right: string): Observable<InvocationComparison> {
    return this.http.get<InvocationComparison>(`${API_BASE}/ai/invocations/compare`, { params: toParams({ left, right }) });
  }

  cycles(page = 0, size = 20): Observable<PageResponse<EvolutionExperiment>> {
    return this.http.get<PageResponse<EvolutionExperiment>>(`${API_BASE}/evolution/cycles`, { params: toParams({ page, size }) });
  }
  timeline(cycleId: string): Observable<{ cycleId: string; events: EvolutionEvent[] }> {
    return this.http.get<{ cycleId: string; events: EvolutionEvent[] }>(`${API_BASE}/evolution/cycles/${encodeURIComponent(cycleId)}/timeline`);
  }
  runCycle(): Observable<CycleResult> {
    return this.http.post<CycleResult>(`${API_BASE}/evolution/cycles`, {});
  }

  policies(): Observable<PolicyState> {
    return this.http.get<PolicyState>(`${API_BASE}/policies`);
  }
  proposeDeterministic(): Observable<CycleResult> {
    return this.http.post<CycleResult>(`${API_BASE}/policies/candidates/deterministic`, {});
  }
  evaluateCandidate(): Observable<EvaluationResult> {
    return this.http.post<EvaluationResult>(`${API_BASE}/policies/candidate/evaluate`, {});
  }
  approve(candidateFingerprint: string, reason: string): Observable<TrafficPolicy> {
    return this.http.post<TrafficPolicy>(`${API_BASE}/policies/candidate/approve`, { candidateFingerprint, reason });
  }
  reject(reason: string): Observable<unknown> {
    return this.http.post(`${API_BASE}/policies/candidate/reject`, { reason });
  }
  rollback(reason: string): Observable<TrafficPolicy> {
    return this.http.post<TrafficPolicy>(`${API_BASE}/policies/rollback`, { reason });
  }

  flags(): Observable<FeatureFlags> {
    return this.http.get<FeatureFlags>(`${API_BASE}/flags`);
  }
  setAi(enabled: boolean): Observable<FeatureFlags> {
    return this.http.put<FeatureFlags>(`${API_BASE}/flags/ai`, { enabled });
  }
  setAutomatic(enabled: boolean): Observable<FeatureFlags> {
    return this.http.put<FeatureFlags>(`${API_BASE}/flags/automatic`, { enabled });
  }
  setModel(model: string): Observable<FeatureFlags> {
    return this.http.put<FeatureFlags>(`${API_BASE}/flags/model`, { model });
  }
  setTriggerThreshold(arrivals: number): Observable<FeatureFlags> {
    return this.http.put<FeatureFlags>(`${API_BASE}/flags/trigger-threshold`, { arrivals });
  }

  events(filter: EventFilterParams): Observable<PageResponse<EvolutionEvent>> {
    return this.http.get<PageResponse<EvolutionEvent>>(`${API_BASE}/diagnostics/events`, { params: toParams(filter) });
  }
}
