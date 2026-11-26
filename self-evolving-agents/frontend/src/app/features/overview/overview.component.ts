import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ApiService } from '../../core/api.service';
import { poll } from '../../core/poller';
import { inject } from '@angular/core';
import { LineChartComponent } from '../../shared/chart.component';
import { StateViewComponent } from '../../shared/state-view.component';

@Component({
  selector: 'app-overview',
  imports: [DecimalPipe, LineChartComponent, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Overview</h1>
    <app-state-view [loading]="summary.loading() && !summary.data()" [error]="summary.data() ? null : summary.error()" (retry)="summary.reload()">
      @if (summary.data(); as s) {
        <p class="muted">Traffic figures are <span class="chip measured">{{ s.metrics.source }}</span> from this instance's last
          {{ s.metrics.windowSeconds }}s (in-process, reset on restart). Refreshes every 3s.
          @if (summary.error()) { <span class="chip bad">refresh failed: {{ summary.error() }}</span> }</p>
        <div class="grid" data-testid="tiles">
          <div class="tile"><div class="label">Incoming RPS</div><div class="value">{{ s.metrics.incomingRps | number: '1.1-1' }}</div></div>
          <div class="tile"><div class="label">Admitted RPS</div><div class="value">{{ s.metrics.admittedRps | number: '1.1-1' }}</div></div>
          <div class="tile"><div class="label">Rejected RPS</div><div class="value">{{ s.metrics.rejectedRps | number: '1.1-1' }}</div></div>
          <div class="tile"><div class="label">Backend p95</div><div class="value">{{ s.metrics.p95LatencyMs | number: '1.0-0' }} ms</div></div>
          <div class="tile"><div class="label">Backend 5xx rate</div><div class="value">{{ s.metrics.backend5xxRate * 100 | number: '1.1-1' }}%</div></div>
          <div class="tile"><div class="label">Queue depth</div><div class="value">{{ s.metrics.queueDepth }}</div></div>
          <div class="tile"><div class="label">Traffic mode</div><div class="value">{{ s.trafficMode }}</div></div>
          <div class="tile"><div class="label">Active policy</div><div class="value">v{{ s.activePolicy.version }}</div>
            <div class="sub">{{ s.activePolicy.admissionPerSecond }}/s · queue {{ s.activePolicy.maxQueue }}</div></div>
          <div class="tile"><div class="label">AI</div><div class="value" data-testid="ai-status">{{ s.flags.aiEnabled ? 'Enabled' : 'Disabled' }}</div></div>
          <div class="tile"><div class="label">Automatic evolution</div><div class="value" data-testid="auto-status">{{ s.flags.automaticEvolutionEnabled ? 'Enabled' : 'Disabled' }}</div></div>
          <div class="tile"><div class="label">Ollama</div><div class="value" data-testid="ollama-status">{{ s.ollama.reachable ? 'Connected' : 'Unreachable' }}</div>
            <div class="sub">{{ s.flags.model }} {{ s.ollama.selectedModelInstalled ? '(installed)' : '(not installed)' }}</div></div>
          <div class="tile"><div class="label">Candidate</div><div class="value">{{ s.candidatePolicy ? 'v' + s.candidatePolicy.version : 'None' }}</div>
            <div class="sub">{{ s.candidatePolicy ? (s.candidateEvaluated ? 'evaluated' : 'not evaluated') : '' }}</div></div>
        </div>
      }
    </app-state-view>
    <h2>Live traffic (last 5 minutes)</h2>
    <app-state-view [loading]="series.loading() && !series.data()" [error]="series.data() ? null : series.error()"
                    [empty]="!!series.data() && !hasTraffic()" emptyText="No traffic observed yet. Generate load via GET /api/v1/shop or the k6 profile."
                    (retry)="series.reload()">
      <div class="grid-2">
        <div class="panel"><app-line-chart title="Requests per second" [labels]="labels()" [series]="rpsSeries()" /></div>
        <div class="panel"><app-line-chart title="Latency and queue" [labels]="labels()" [series]="latencySeries()" [secondaryAxis]="true" /></div>
      </div>
    </app-state-view>
  `,
})
export class OverviewComponent {
  private readonly api = inject(ApiService);
  protected readonly summary = poll(() => this.api.dashboard(), 3000);
  protected readonly series = poll(() => this.api.timeseries(300, 5), 5000);

  protected readonly labels = computed(() =>
    (this.series.data()?.points ?? []).map((p) => new Date(p.time).toLocaleTimeString()));
  protected readonly hasTraffic = computed(() => (this.series.data()?.points ?? []).some((p) => p.incoming > 0 || p.admitted > 0));
  protected readonly rpsSeries = computed(() => {
    const d = this.series.data();
    const step = d?.stepSeconds || 1;
    const pts = d?.points ?? [];
    return [
      { label: 'Incoming', data: pts.map((p) => p.incoming / step), color: '#1565c0' },
      { label: 'Admitted', data: pts.map((p) => p.admitted / step), color: '#2e7d32' },
      { label: 'Rejected', data: pts.map((p) => p.rejected / step), color: '#c62828' },
    ];
  });
  protected readonly latencySeries = computed(() => {
    const pts = this.series.data()?.points ?? [];
    return [
      { label: 'Backend p95 (ms)', data: pts.map((p) => p.p95LatencyMs), color: '#ef6c00' },
      { label: 'Queue depth', data: pts.map((p) => p.queueDepth), color: '#6a1b9a', yAxisID: 'y1' },
    ];
  });
}
