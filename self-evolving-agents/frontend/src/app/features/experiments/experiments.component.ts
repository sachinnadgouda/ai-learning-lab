import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ApiService } from '../../core/api.service';
import { EvaluationOutcome, EvolutionExperiment } from '../../core/models';
import { poll } from '../../core/poller';
import { StateViewComponent } from '../../shared/state-view.component';

interface Row { label: string; baseline: string; candidate: string; better: boolean | null }

@Component({
  selector: 'app-experiments',
  imports: [DatePipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .layout { display: grid; grid-template-columns: 320px 1fr; gap: 16px; align-items: start; }
    .exp { padding: 10px; border-radius: 8px; cursor: pointer; border: 1px solid transparent; }
    .exp.selected { border-color: var(--mat-sys-primary); background: var(--mat-sys-surface-container); }
    td.better { background: rgba(76, 175, 80, .18); font-weight: 600; } td.worse { background: rgba(244, 67, 54, .14); }
    @media (max-width: 1000px) { .layout { grid-template-columns: 1fr; } }
  `,
  template: `
    <h1>Experiment Comparison</h1>
    <div class="layout">
      <section class="panel">
        <app-state-view [loading]="data.loading() && !data.data()" [error]="data.data() ? null : data.error()"
                        [empty]="!!data.data() && data.data()!.items.length === 0" emptyText="No experiments recorded yet." (retry)="data.reload()">
          @for (x of data.data()?.items; track x.cycleId) {
            <div class="exp" [class.selected]="x.cycleId === selectedId()" data-testid="experiment-item" (click)="selectedId.set(x.cycleId)">
              <div><span class="chip">{{ x.status }}</span> <span class="muted">{{ x.decision ?? '—' }}</span></div>
              <div class="muted">{{ x.startedAt | date: 'medium' }}</div>
              <div class="mono muted">v{{ x.baseline.version }} → {{ x.candidate ? 'v' + x.candidate.version : '—' }}</div>
            </div>
          }
        </app-state-view>
      </section>
      <section class="panel">
        @if (selected(); as x) {
          <h2 style="margin-top:0">Cycle {{ x.cycleId.substring(0, 8) }} — {{ x.status }}</h2>
          <p>{{ x.rationale }} <span class="muted">{{ x.outcomeNote }}</span></p>
          @if (x.evaluation; as e) {
            <p><span class="chip sim">SIMULATED</span> These figures come from a deterministic replay of
              <strong>{{ e.traceSource }}</strong> ({{ x.observedArrivals }} observed arrivals) against a backend capacity of
              {{ e.backendCapacity }}/s. They are <strong>not</strong> measured HTTP results.
              Measured HTTP metrics are shown on the Overview page (<span class="chip measured">OBSERVED</span>).</p>
            <table class="simple" data-testid="comparison-table">
              <thead><tr><th>Metric</th><th>Baseline v{{ e.baselineVersion }}</th><th>Candidate v{{ e.candidateVersion }}</th></tr></thead>
              <tbody>
                @for (r of rows(); track r.label) {
                  <tr><td>{{ r.label }}</td><td>{{ r.baseline }}</td>
                    <td [class.better]="r.better === true" [class.worse]="r.better === false">{{ r.candidate }}</td></tr>
                }
              </tbody>
            </table>
            <h3>Safety constraints and final decision</h3>
            <p><span class="chip" [class]="'chip ' + (e.valid ? 'ok' : 'bad')" data-testid="final-decision">{{ e.valid ? 'PASSED all safety gates' : 'FAILED safety gates' }}</span></p>
            @if (e.reasons.length) { <ul>@for (reason of e.reasons; track reason) { <li>{{ reason }}</li> }</ul> }
            <p class="muted">{{ e.methodology }}</p>
          } @else {
            <p class="muted" data-testid="no-evaluation">This cycle has no evaluation (no candidate was produced).</p>
          }
        } @else {
          <p class="muted">Select an experiment.</p>
        }
      </section>
    </div>
  `,
})
export class ExperimentsComponent {
  private readonly api = inject(ApiService);
  protected readonly data = poll(() => this.api.cycles(0, 50), 10000);
  readonly selectedId = signal<string | null>(null);

  readonly selected = computed<EvolutionExperiment | null>(() => {
    const items = this.data.data()?.items ?? [];
    return items.find((x) => x.cycleId === this.selectedId()) ?? items.find((x) => x.evaluation) ?? null;
  });

  readonly rows = computed<Row[]>(() => {
    const e = this.selected()?.evaluation;
    return e ? rowsOf(e.baseline, e.candidate) : [];
  });
}

export function rowsOf(b: EvaluationOutcome, c: EvaluationOutcome): Row[] {
  const num = (v: number, d = 0) => (Math.round(v * 10 ** d) / 10 ** d).toString();
  const pct = (v: number) => `${num(v * 100, 1)}%`;
  const row = (label: string, bv: number, cv: number, fmt: (v: number) => string, lowerIsBetter: boolean | null): Row => ({
    label, baseline: fmt(bv), candidate: fmt(cv),
    better: lowerIsBetter === null || bv === cv ? null : lowerIsBetter ? cv < bv : cv > bv,
  });
  return [
    row('Throughput — admitted requests', b.admitted, c.admitted, num, false),
    row('Estimated backend p95 latency (ms)', b.estimatedP95BackendMs, c.estimatedP95BackendMs, (v) => num(v), true),
    row('Estimated backend error rate', b.estimatedErrorRate, c.estimatedErrorRate, pct, true),
    row('Maximum queue depth', b.maxQueueDepth, c.maxQueueDepth, num, true),
    row('Queue still waiting at end', b.queuedAtEnd, c.queuedAtEnd, num, true),
    row('Estimated p95 queue wait (s)', b.estimatedP95WaitSeconds, c.estimatedP95WaitSeconds, (v) => num(v, 1), true),
    row('Rejections', b.rejected, c.rejected, num, true),
    row('Backend capacity overflow', b.capacityOverflow, c.capacityOverflow, num, true),
  ];
}
