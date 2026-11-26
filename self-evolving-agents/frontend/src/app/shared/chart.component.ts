import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, effect, input, viewChild } from '@angular/core';
import { Chart, ChartConfiguration, registerables } from 'chart.js';

Chart.register(...registerables);

export interface ChartSeries {
  label: string;
  data: number[];
  color: string;
  yAxisID?: string;
}

@Component({
  selector: 'app-line-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `:host { display: block; position: relative; height: 240px; }`,
  template: `<canvas #canvas role="img" [attr.aria-label]="title()"></canvas>`,
})
export class LineChartComponent implements OnDestroy {
  readonly title = input.required<string>();
  readonly labels = input.required<string[]>();
  readonly series = input.required<ChartSeries[]>();
  readonly secondaryAxis = input(false);
  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private chart: Chart | null = null;

  constructor() {
    effect(() => {
      const labels = this.labels();
      const series = this.series();
      const el = this.canvas().nativeElement;
      const datasets = series.map((s) => ({
        label: s.label, data: s.data, borderColor: s.color, backgroundColor: s.color, borderWidth: 2,
        pointRadius: 0, tension: 0.25, yAxisID: s.yAxisID ?? 'y',
      }));
      if (this.chart) {
        this.chart.data.labels = labels;
        this.chart.data.datasets = datasets;
        try {
          this.chart.update('none');
        } catch {
          // rendering is best-effort when no layout engine is available
        }
        return;
      }
      const config: ChartConfiguration<'line'> = {
        type: 'line',
        data: { labels, datasets },
        options: {
          responsive: true, maintainAspectRatio: false, animation: false,
          interaction: { mode: 'index', intersect: false },
          plugins: { legend: { position: 'bottom' } },
          scales: {
            x: { ticks: { maxTicksLimit: 8 } },
            y: { beginAtZero: true, position: 'left' },
            ...(this.secondaryAxis() ? { y1: { beginAtZero: true, position: 'right' as const, grid: { drawOnChartArea: false } } } : {}),
          },
        },
      };
      try {
        this.chart = new Chart(el, config);
      } catch {
        this.chart = null; // canvas/layout unavailable (e.g. jsdom)
      }
    });
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }
}
