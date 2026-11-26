import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatTooltip } from '@angular/material/tooltip';

@Component({
  selector: 'app-copy-button',
  imports: [MatIconButton, MatIcon, MatTooltip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button mat-icon-button type="button" (click)="copy()" [attr.aria-label]="'Copy ' + label()"
            [matTooltip]="copied() ? 'Copied' : 'Copy ' + label()">
      <mat-icon>{{ copied() ? 'check' : 'content_copy' }}</mat-icon>
    </button>`,
})
export class CopyButtonComponent {
  readonly text = input.required<string>();
  readonly label = input('to clipboard');
  readonly copied = signal(false);

  async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.text());
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1500);
    } catch {
      this.copied.set(false);
    }
  }
}
