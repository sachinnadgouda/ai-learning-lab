import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatIcon } from '@angular/material/icon';
import { MatButton } from '@angular/material/button';
import { ApiService } from '../../core/api.service';
import { EvolutionEvent, EvolutionExperiment } from '../../core/models';
import { describeError, poll } from '../../core/poller';
import { stageClass, stageIcon } from '../../shared/badges';
import { StateViewComponent } from '../../shared/state-view.component';

export const HAPPY_PATH = ['OBSERVED', 'TRIGGERED', 'AI_CALLED', 'PROPOSED', 'EVALUATED', 'APPROVED', 'ACTIVATED'];
export const TERMINAL = ['NO_CHANGE', 'INSUFFICIENT_DATA', 'REJECTED', 'FAILED', 'ROLLED_BACK'];

@Component({
  selector: 'app-timeline',
  imports: [DatePipe, MatIcon, MatButton, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .layout { display: grid; grid-template-columns: 320px 1fr; gap: 16px; align-items: start; }
    .cycle { padding: 10px; border-radius: 8px; cursor: pointer; border: 1px solid transparent; }
    .cycle:hover { background: var(--mat-sys-surface-container); }
    .cycle.selected { border-color: var(--mat-sys-primary); background: var(--mat-sys-surface-container); }
    .path { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 16px; align-items: center; }
    .step { padding: 4px 10px; border-radius: 14px; background: var(--mat-sys-surface-container-high); opacity: .55; font-size: 12px; }
    .step.reached { opacity: 1; background: #c8e6c9; color: #1b5e20; }
    .step.terminal { opacity: 1; background: #ffe0b2; color: #e65100; }
    .step.terminal.bad { background: #ffcdd2; color: #b71c1c; }
    .event { display: grid; grid-template-columns: 32px 1fr; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--mat-sys-outline-variant); }
    .event .when { font-size: 12px; opacity: .7; }
    pre { margin: 4px 0 0; font-size: 12px; background: var(--mat-sys-surface-container); padding: 8px; border-radius: 6px; white-space: pre-wrap; }
    @media (max-width: 1000px) { .layout { grid-template-columns: 1fr; } }
  `,
  template: `
    <h1>Evolution Timeline</h1>
    <div class="layout">
      <section class="panel" aria-label="Evolution cycles">
        <app-state-view [loading]="cycles.loading() && !cycles.data()" [error]="cycles.data() ? null : cycles.error()"
                        [empty]="!!cycles.data() && cycles.data()!.items.length === 0" emptyText="No evolution cycles yet. Trigger one from Policies."
                        (retry)="cycles.reload()">
          @for (c of cycles.data()?.items; track c.cycleId) {
            <div class="cycle" [class.selected]="c.cycleId === selected()" data-testid="cycle-item" (click)="select(c)">
              <div><span class="chip" [class]="'chip ' + cls(c.status)">{{ c.status }}</span> <span class="muted">{{ c.trigger }}</span></div>
              <div class="muted">{{ c.startedAt | date: 'medium' }}</div>
              <div class="mono muted">{{ c.cycleId.substring(0, 8) }}</div>
            </div>
          }
        </app-state-view>
      </section>
      <section class="panel" aria-label="Timeline">
        <app-state-view [loading]="loading()" [error]="error()" [empty]="!selected()" emptyText="Select a cycle to see its lifecycle." (retry)="reload()">
          <div class="path" data-testid="path">
            @for (s of happyPath; track s) {
              <span class="step" [class.reached]="reached().has(s)">{{ s }}</span>
            }
            @for (t of terminals(); track t) {
              <span class="step terminal" [class.bad]="t === 'FAILED' || t === 'REJECTED'">{{ t }}</span>
            }
          </div>
          @for (e of events(); track $index) {
            <div class="event" data-testid="timeline-event">
              <mat-icon [class]="'chip ' + cls(e.stage)" style="width:28px;height:28px;border-radius:50%;padding:2px">{{ icon(e.stage) }}</mat-icon>
              <div>
                <div><strong>{{ e.stage }}</strong> <span class="chip">{{ e.component }}</span>
                  @if (e.level !== 'INFO') { <span class="chip warn">{{ e.level }}</span> }</div>
                <div class="when">{{ e.time | date: 'yyyy-MM-dd HH:mm:ss.SSS' }}
                  @if (e.policyVersion !== null) { · policy v{{ e.policyVersion }} }
                  @if (e.aiInvocationId) { · AI {{ e.aiInvocationId.substring(0, 8) }} }</div>
                <div>{{ e.message }}</div>
                @if (hasData(e)) { <pre>{{ json(e.data) }}</pre> }
              </div>
            </div>
          }
        </app-state-view>
        <button mat-button type="button" (click)="cycles.reload()">Refresh</button>
      </section>
    </div>
  `,
})
export class TimelineComponent {
  private readonly api = inject(ApiService);
  protected readonly cycles = poll(() => this.api.cycles(0, 30), 8000);
  protected readonly happyPath = HAPPY_PATH;
  readonly selected = signal<string | null>(null);
  readonly events = signal<EvolutionEvent[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly reached = computed(() => new Set(this.events().map((e) => e.stage)));
  readonly terminals = computed(() => TERMINAL.filter((t) => this.reached().has(t)));

  protected readonly cls = stageClass;
  protected readonly icon = stageIcon;

  select(c: EvolutionExperiment): void {
    this.selected.set(c.cycleId);
    this.reload();
  }

  reload(): void {
    const id = this.selected();
    if (!id) return;
    this.loading.set(true);
    this.error.set(null);
    this.api.timeline(id).subscribe({
      next: (t) => {
        this.events.set(t.events);
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.error.set(describeError(e));
        this.loading.set(false);
      },
    });
  }

  hasData(e: EvolutionEvent): boolean {
    return Object.keys(e.data ?? {}).length > 0;
  }

  json(value: unknown): string {
    return JSON.stringify(value, null, 2);
  }
}
