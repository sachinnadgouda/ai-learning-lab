import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSelect, MatOption } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { EvolutionEvent, PageResponse } from '../../core/models';
import { describeError } from '../../core/poller';
import { StateViewComponent } from '../../shared/state-view.component';

const RANGES: Record<string, number> = { '15m': 15, '1h': 60, '6h': 360, '24h': 1440, '7d': 10080, all: 0 };

@Component({
  selector: 'app-logs',
  imports: [DatePipe, FormsModule, MatButton, MatFormFieldModule, MatInput, MatPaginator, MatSelect, MatOption, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `.filters { display: flex; flex-wrap: wrap; gap: 8px; } .filters mat-form-field { width: 170px; } td.msg { max-width: 560px; word-break: break-word; }`,
  template: `
    <h1>Logs &amp; Diagnostics</h1>
    <p class="muted">Evolution and administrative events recorded by the application (not raw application logs). Secrets are never stored here.</p>
    <div class="panel">
      <div class="filters">
        <mat-form-field appearance="outline"><mat-label>Time range</mat-label>
          <mat-select [ngModel]="range()" (ngModelChange)="range.set($event); apply()">
            @for (r of rangeKeys; track r) { <mat-option [value]="r">{{ r }}</mat-option> }
          </mat-select></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Level</mat-label>
          <mat-select [ngModel]="level()" (ngModelChange)="level.set($event); apply()">
            <mat-option value="">Any</mat-option><mat-option value="INFO">INFO</mat-option><mat-option value="WARN">WARN</mat-option><mat-option value="ERROR">ERROR</mat-option>
          </mat-select></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Component</mat-label>
          <input matInput name="component" [ngModel]="component()" (ngModelChange)="component.set($event)" (keyup.enter)="apply()" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Evolution cycle</mat-label>
          <input matInput name="cycle" [ngModel]="cycleId()" (ngModelChange)="cycleId.set($event)" (keyup.enter)="apply()" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>AI invocation</mat-label>
          <input matInput name="invocation" [ngModel]="invocationId()" (ngModelChange)="invocationId.set($event)" (keyup.enter)="apply()" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Policy version</mat-label>
          <input matInput type="number" name="policy" [ngModel]="policyVersion()" (ngModelChange)="policyVersion.set($event)" (keyup.enter)="apply()" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Search text</mat-label>
          <input matInput name="q" data-testid="log-search" [ngModel]="q()" (ngModelChange)="q.set($event)" (keyup.enter)="apply()" /></mat-form-field>
        <button mat-stroked-button type="button" (click)="apply()">Apply</button>
      </div>
    </div>
    <app-state-view [loading]="loading() && !data()" [error]="data() ? null : error()" [empty]="!!data() && data()!.items.length === 0"
                    emptyText="No events match the filters." (retry)="load()">
      <table class="simple" data-testid="log-table">
        <thead><tr><th>Time</th><th>Level</th><th>Component</th><th>Stage</th><th>Message</th><th>Correlation</th></tr></thead>
        <tbody>
          @for (e of data()?.items; track $index) {
            <tr>
              <td class="mono">{{ e.time | date: 'MM-dd HH:mm:ss' }}</td>
              <td><span class="chip" [class]="'chip ' + levelClass(e)">{{ e.level }}</span></td>
              <td>{{ e.component }}</td><td>{{ e.stage }}</td><td class="msg">{{ e.message }}</td>
              <td class="mono">{{ e.cycleId?.substring(0, 8) }} {{ e.aiInvocationId?.substring(0, 8) }} {{ e.policyVersion !== null ? 'v' + e.policyVersion : '' }}</td>
            </tr>
          }
        </tbody>
      </table>
      <mat-paginator [length]="data()?.totalItems ?? 0" [pageSize]="50" [pageIndex]="pageIndex()" [hidePageSize]="true" (page)="onPage($event)" />
    </app-state-view>
  `,
})
export class LogsComponent {
  private readonly api = inject(ApiService);
  protected readonly rangeKeys = Object.keys(RANGES);
  readonly range = signal('1h');
  readonly level = signal('');
  readonly component = signal('');
  readonly cycleId = signal('');
  readonly invocationId = signal('');
  readonly policyVersion = signal<number | null>(null);
  readonly q = signal('');
  readonly pageIndex = signal(0);
  readonly data = signal<PageResponse<EvolutionEvent> | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  constructor() {
    this.load();
    const timer = setInterval(() => this.load(true), 10000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  apply(): void {
    this.pageIndex.set(0);
    this.load();
  }

  onPage(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.load();
  }

  load(silent = false): void {
    if (!silent) this.loading.set(true);
    const minutes = RANGES[this.range()] ?? 0;
    this.api.events({
      page: this.pageIndex(), size: 50, level: this.level(), component: this.component(), cycleId: this.cycleId(),
      aiInvocationId: this.invocationId(), policyVersion: this.policyVersion(), q: this.q(),
      from: minutes > 0 ? new Date(Date.now() - minutes * 60_000).toISOString() : undefined,
    }).subscribe({
      next: (d) => { this.data.set(d); this.error.set(null); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(describeError(e)); this.loading.set(false); },
    });
  }

  levelClass(e: EvolutionEvent): string {
    return e.level === 'ERROR' ? 'bad' : e.level === 'WARN' ? 'warn' : 'info';
  }
}
