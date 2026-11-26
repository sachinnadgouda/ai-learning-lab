import { ChangeDetectionStrategy, Component, computed, input, linkedSignal } from '@angular/core';
import { MatIcon } from '@angular/material/icon';
import { MatIconButton } from '@angular/material/button';
import { CopyButtonComponent } from './copy-button.component';
import { formatJson, highlightJson, escapeHtml } from './json-format';

/** Collapsible, copyable, syntax-highlighted display of an exact recorded payload. */
@Component({
  selector: 'app-json-view',
  imports: [MatIcon, MatIconButton, CopyButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; margin-bottom: 12px; }
    .head { display: flex; align-items: center; gap: 4px; }
    .head h4 { margin: 0; flex: 1; font-weight: 500; }
    pre { margin: 0; padding: 12px; border-radius: 8px; overflow: auto; max-height: 360px; font-size: 12.5px;
          background: var(--mat-sys-surface-container); white-space: pre-wrap; word-break: break-word; }
    .empty { opacity: .6; font-style: italic; }
    :host ::ng-deep .tok-key { color: #1565c0; } :host ::ng-deep .tok-string { color: #2e7d32; }
    :host ::ng-deep .tok-number { color: #ef6c00; } :host ::ng-deep .tok-bool { color: #6a1b9a; }
    :host ::ng-deep .tok-null { color: #757575; }
  `,
  template: `
    <div class="head">
      <button mat-icon-button type="button" (click)="open.set(!open())" [attr.aria-expanded]="open()"
              [attr.aria-label]="(open() ? 'Collapse ' : 'Expand ') + title()">
        <mat-icon>{{ open() ? 'expand_less' : 'expand_more' }}</mat-icon>
      </button>
      <h4>{{ title() }}</h4>
      <app-copy-button [text]="formatted().text" [label]="title()" />
    </div>
    @if (open()) {
      @if (formatted().text) {
        <pre data-testid="json-body" [innerHTML]="html()"></pre>
      } @else {
        <p class="empty">No content recorded.</p>
      }
    }
  `,
})
export class JsonViewComponent {
  readonly title = input.required<string>();
  readonly content = input<string | null | undefined>(null);
  readonly startOpen = input(true);
  readonly open = linkedSignal(() => this.startOpen());

  readonly formatted = computed(() => formatJson(this.content()));
  readonly html = computed(() => {
    const f = this.formatted();
    return f.isJson ? highlightJson(f.text) : escapeHtml(f.text);
  });
}
