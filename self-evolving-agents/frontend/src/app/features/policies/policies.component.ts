import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButton } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { PolicyCandidate, TrafficPolicy, fingerprintOf } from '../../core/models';
import { describeError, poll } from '../../core/poller';
import { ConfirmData, ConfirmDialogComponent, ConfirmResult } from '../../shared/confirm-dialog.component';
import { StateViewComponent } from '../../shared/state-view.component';

interface Diff { name: string; active: number | string; candidate: number | string; changed: boolean }

@Component({
  selector: 'app-policies',
  imports: [DatePipe, MatButton, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Policy Management</h1>
    <app-state-view [loading]="state.loading() && !state.data()" [error]="state.data() ? null : state.error()" (retry)="state.reload()">
      @if (state.data(); as s) {
        <div class="panel">
          <div class="row">
            <h2 style="margin:0;flex:1">Active policy v{{ s.active.version }}</h2>
            <button mat-stroked-button type="button" data-testid="propose-deterministic" (click)="proposeDeterministic()" [disabled]="!!s.candidate || busy()">Propose deterministic candidate</button>
            <button mat-stroked-button type="button" data-testid="run-cycle" (click)="runCycle()" [disabled]="!!s.candidate || busy()">Run AI evolution cycle</button>
            <button mat-stroked-button type="button" color="warn" data-testid="rollback" (click)="rollback()" [disabled]="s.history.length === 0 || busy()">Roll back</button>
          </div>
          <p class="muted">{{ s.active.mode }} · {{ s.active.admissionPerSecond }} admissions/s · queue {{ s.active.maxQueue }} · {{ s.active.simulatedWorkMs }} ms simulated work</p>
          @if (message()) { <p role="status">{{ message() }}</p> }
          @if (actionError()) { <p role="alert" style="color:var(--mat-sys-error)">{{ actionError() }}</p> }
        </div>

        <div class="panel" data-testid="candidate-panel">
          <h2 style="margin-top:0">Candidate policy</h2>
          @if (s.candidate; as c) {
            <p>v{{ c.policy.version }} from <span class="chip">{{ c.source }}</span> · baseline v{{ c.baselineVersion }} · created {{ c.createdAt | date: 'medium' }}</p>
            <p class="muted">{{ c.rationale }}</p>
            <table class="simple" data-testid="diff-table">
              <thead><tr><th>Parameter</th><th>Active (v{{ s.active.version }})</th><th>Candidate (v{{ c.policy.version }})</th></tr></thead>
              <tbody>
                @for (d of diffs(); track d.name) {
                  <tr><td>{{ d.name }}</td><td>{{ d.active }}</td><td [class.changed]="d.changed">{{ d.candidate }}</td></tr>
                }
              </tbody>
            </table>
            <h3>Safety gates</h3>
            @if (s.evaluation; as e) {
              <p><span class="chip" [class]="'chip ' + (e.valid ? 'ok' : 'bad')" data-testid="evaluation-outcome">{{ e.valid ? 'PASSED' : 'FAILED' }}</span>
                <span class="chip sim">SIMULATED</span> evaluated {{ e.evaluatedAt | date: 'medium' }} against {{ e.traceSource }}</p>
              @if (e.reasons.length) { <ul>@for (r of e.reasons; track r) { <li>{{ r }}</li> }</ul> }
              <p class="muted">{{ e.methodology }}</p>
            } @else {
              <p data-testid="not-evaluated"><span class="chip warn">NOT EVALUATED</span> The candidate cannot be approved until it has been evaluated.</p>
              <button mat-stroked-button type="button" data-testid="evaluate" (click)="evaluate()" [disabled]="busy()">Evaluate now</button>
            }
            <div class="row" style="margin-top:12px">
              <button mat-flat-button type="button" data-testid="approve" (click)="approve(c)" [disabled]="!s.approvable || busy()">Approve and activate</button>
              <button mat-stroked-button type="button" color="warn" data-testid="reject" (click)="reject()" [disabled]="busy()">Reject</button>
              @if (!s.approvable) { <span class="muted">Approval is disabled: candidate is not evaluated or the evaluation failed.</span> }
            </div>
          } @else {
            <p class="muted" data-testid="no-candidate">No candidate is pending. AI proposals and deterministic proposals appear here.</p>
          }
        </div>

        <div class="panel">
          <h2 style="margin-top:0">Policy history</h2>
          @if (s.history.length === 0) { <p class="muted">No previous policies.</p> } @else {
            <table class="simple">
              <thead><tr><th>Version</th><th>Mode</th><th>Admission/s</th><th>Queue</th><th>Work ms</th></tr></thead>
              <tbody>@for (h of s.history; track $index) {
                <tr><td>v{{ h.version }}</td><td>{{ h.mode }}</td><td>{{ h.admissionPerSecond }}</td><td>{{ h.maxQueue }}</td><td>{{ h.simulatedWorkMs }}</td></tr>
              }</tbody>
            </table>
          }
        </div>
      }
    </app-state-view>
  `,
})
export class PoliciesComponent {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  protected readonly state = poll(() => this.api.policies(), 5000);
  readonly busy = signal(false);
  readonly message = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);

  readonly diffs = computed<Diff[]>(() => {
    const s = this.state.data();
    if (!s?.candidate) return [];
    return diffOf(s.active, s.candidate.policy);
  });

  proposeDeterministic(): void {
    this.perform(this.api.proposeDeterministic(), (r) => `Candidate created: ${r.note || r.status}`);
  }

  runCycle(): void {
    this.perform(this.api.runCycle(), (r) => `Cycle ${r.cycleId.substring(0, 8)}: ${r.status} (${r.decision ?? 'no decision'})`);
  }

  evaluate(): void {
    this.perform(this.api.evaluateCandidate(), (e) => `Evaluation ${e.valid ? 'passed' : 'failed'}`);
  }

  approve(candidate: PolicyCandidate): void {
    const fingerprint = fingerprintOf(candidate.policy);
    this.confirm({
      title: `Approve candidate v${candidate.policy.version}?`,
      message: 'This activates the evaluated candidate for all traffic immediately. You can roll back afterwards.',
      confirmLabel: 'Approve and activate', askReason: true,
    }, (reason) => this.perform(this.api.approve(fingerprint, reason), (p) => `Policy v${p.version} is now active`));
  }

  reject(): void {
    this.confirm({ title: 'Reject candidate?', message: 'The candidate is discarded; the active policy is unchanged.',
      confirmLabel: 'Reject', askReason: true, destructive: true },
      (reason) => this.perform(this.api.reject(reason), () => 'Candidate rejected'));
  }

  rollback(): void {
    this.confirm({ title: 'Roll back to the previous policy?', message: 'Traffic will immediately use the previous policy.',
      confirmLabel: 'Roll back', askReason: true, destructive: true },
      (reason) => this.perform(this.api.rollback(reason), (p) => `Rolled back to policy v${p.version}`));
  }

  private confirm(data: ConfirmData, action: (reason: string) => void): void {
    this.dialog.open<ConfirmDialogComponent, ConfirmData, ConfirmResult>(ConfirmDialogComponent, { data })
      .afterClosed().subscribe((result) => {
        if (result) action(result.reason);
      });
  }

  private perform<T>(call: Observable<T>, success: (value: T) => string): void {
    this.busy.set(true);
    this.actionError.set(null);
    call.subscribe({
      next: (value) => {
        const text = success(value);
        this.message.set(text);
        this.snack.open(text, 'OK', { duration: 4000 });
        this.busy.set(false);
        this.state.reload();
      },
      error: (e: unknown) => {
        this.actionError.set(describeError(e));
        this.busy.set(false);
        this.state.reload();
      },
    });
  }
}

export function diffOf(active: TrafficPolicy, candidate: TrafficPolicy): Diff[] {
  const rows: [string, keyof TrafficPolicy][] = [
    ['Mode', 'mode'], ['Admission per second', 'admissionPerSecond'], ['Max queue', 'maxQueue'], ['Simulated work (ms)', 'simulatedWorkMs'],
  ];
  return rows.map(([name, key]) => ({ name, active: active[key], candidate: candidate[key], changed: active[key] !== candidate[key] }));
}
