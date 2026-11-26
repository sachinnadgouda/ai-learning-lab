import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatSelect, MatOption } from '@angular/material/select';
import { MatSlideToggle } from '@angular/material/slide-toggle';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { FeatureFlags } from '../../core/models';
import { describeError, poll } from '../../core/poller';
import { StateViewComponent } from '../../shared/state-view.component';

@Component({
  selector: 'app-settings',
  imports: [FormsModule, MatButton, MatFormFieldModule, MatInput, MatSelect, MatOption, MatSlideToggle, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Settings</h1>
    <app-state-view [loading]="flags.loading() && !flags.data()" [error]="flags.data() ? null : flags.error()" (retry)="flags.reload()">
      @if (flags.data(); as f) {
        <div class="panel">
          <h2 style="margin-top:0">Feature flags</h2>
          <div class="row">
            <mat-slide-toggle data-testid="ai-toggle" [checked]="f.aiEnabled" [disabled]="busy()" (change)="apply(api.setAi($event.checked))">AI enabled</mat-slide-toggle>
            <span class="muted">source: {{ f.aiSource }} — when off, no Ollama calls are made. The active policy is not rolled back.</span>
          </div>
          <div class="row" style="margin-top:12px">
            <mat-slide-toggle data-testid="auto-toggle" [checked]="f.automaticEvolutionEnabled" [disabled]="busy()" (change)="apply(api.setAutomatic($event.checked))">Automatic evolution</mat-slide-toggle>
            <span class="muted">source: {{ f.automaticSource }} — scheduled cycles still require AI and human approval.</span>
          </div>
          @if (!f.aiEnabled && f.automaticEvolutionEnabled) {
            <p class="chip warn" data-testid="auto-without-ai">Automatic evolution is on but AI is off: scheduled runs will do nothing.</p>
          }
        </div>
        <div class="panel">
          <h2 style="margin-top:0">Ollama</h2>
          @if (ai.data(); as a) {
            <p>Connection: <span class="chip" [class]="'chip ' + (a.ollama.reachable ? 'ok' : 'bad')" data-testid="ollama-connection">{{ a.ollama.reachable ? 'Connected' : 'Unreachable' }}</span>
              <span class="muted">{{ a.ollama.baseUrl }} — {{ a.ollama.detail }}</span></p>
            @if (!a.ollama.reachable) { <p class="muted">Start it with <code>docker compose up -d ollama</code> and pull the model: <code>docker compose --profile setup run --rm ollama-pull</code></p> }
            <mat-form-field appearance="outline">
              <mat-label>Model</mat-label>
              <mat-select [value]="f.model" [disabled]="busy()" (selectionChange)="apply(api.setModel($event.value))" data-testid="model-select">
                @for (m of modelChoices(); track m) { <mat-option [value]="m">{{ m }} {{ a.ollama.installedModels.includes(m) ? '' : '(not installed)' }}</mat-option> }
              </mat-select>
            </mat-form-field>
          }
          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Evolution trigger threshold (arrivals in last 60s)</mat-label>
              <input matInput type="number" min="20" max="100000" name="threshold" [ngModel]="threshold() ?? f.triggerArrivals" (ngModelChange)="threshold.set($event)" />
            </mat-form-field>
            <button mat-stroked-button type="button" (click)="apply(api.setTriggerThreshold(threshold() ?? f.triggerArrivals))" [disabled]="busy()">Save threshold</button>
          </div>
          @if (message()) { <p role="status">{{ message() }}</p> }
          @if (error()) { <p role="alert" style="color:var(--mat-sys-error)">{{ error() }}</p> }
        </div>
      }
    </app-state-view>
    <div class="panel">
      <h2 style="margin-top:0">Effective runtime settings</h2>
      <app-state-view [loading]="settings.loading() && !settings.data()" [error]="settings.data() ? null : settings.error()" (retry)="settings.reload()">
        <table class="simple" data-testid="runtime-table">
          <tbody>@for (r of settingRows(); track r.key) { <tr><td>{{ r.key }}</td><td class="mono">{{ r.value }}</td></tr> }</tbody>
        </table>
      </app-state-view>
    </div>
  `,
})
export class SettingsComponent {
  protected readonly api = inject(ApiService);
  protected readonly flags = poll(() => this.api.flags(), 0);
  protected readonly ai = poll(() => this.api.aiStatus(), 15000);
  protected readonly settings = poll(() => this.api.settings(), 0);
  readonly busy = signal(false);
  readonly message = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly threshold = signal<number | null>(null);

  readonly modelChoices = computed(() => {
    const allowed = (this.settings.data()?.['allowedModels'] as string[] | undefined) ?? [];
    const set = new Set<string>([...allowed, ...(this.ai.data()?.ollama.installedModels ?? [])]);
    const current = this.flags.data()?.model;
    if (current) set.add(current);
    return [...set];
  });

  readonly settingRows = computed(() =>
    Object.entries(this.settings.data() ?? {}).map(([key, value]) => ({
      key, value: typeof value === 'object' ? JSON.stringify(value) : String(value),
    })));

  apply(call: Observable<FeatureFlags>): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: () => {
        this.message.set('Saved. Changes apply immediately; no restart required.');
        this.busy.set(false);
        this.threshold.set(null);
        this.flags.reload();
        this.ai.reload();
      },
      error: (e: unknown) => {
        this.error.set(describeError(e));
        this.busy.set(false);
        this.flags.reload();
      },
    });
  }
}
