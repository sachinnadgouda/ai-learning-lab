import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatProgressBar } from '@angular/material/progress-bar';

/** Shared loading / error / empty wrapper. Content is projected when none of those states apply. */
@Component({
  selector: 'app-state-view',
  imports: [MatProgressBar, MatButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `.msg { padding: 24px; text-align: center; opacity: .8; } .err { color: var(--mat-sys-error); opacity: 1; }`,
  template: `
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" aria-label="Loading" />
    } @else if (error(); as e) {
      <div class="msg err" role="alert" data-testid="error-state">
        {{ e }} <br /><button mat-button type="button" (click)="retry.emit()">Retry</button>
      </div>
    } @else if (empty()) {
      <div class="msg" data-testid="empty-state">{{ emptyText() }}</div>
    } @else {
      <ng-content />
    }`,
})
export class StateViewComponent {
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  readonly empty = input(false);
  readonly emptyText = input('Nothing to show yet.');
  readonly retry = output<void>();
}
