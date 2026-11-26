import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogActions, MatDialogContent, MatDialogRef, MatDialogTitle } from '@angular/material/dialog';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';

export interface ConfirmData {
  title: string;
  message: string;
  confirmLabel: string;
  askReason?: boolean;
  destructive?: boolean;
}

export interface ConfirmResult {
  reason: string;
}

@Component({
  selector: 'app-confirm-dialog',
  imports: [MatDialogTitle, MatDialogContent, MatDialogActions, MatButton, MatFormField, MatLabel, MatInput, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (data.askReason) {
        <mat-form-field appearance="outline" style="width:100%">
          <mat-label>Reason (audited)</mat-label>
          <input matInput [ngModel]="reason()" (ngModelChange)="reason.set($event)" maxlength="500" />
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" (click)="ref.close()">Cancel</button>
      <button mat-flat-button type="button" data-testid="confirm" [color]="data.destructive ? 'warn' : 'primary'"
              (click)="ref.close({ reason: reason() })">{{ data.confirmLabel }}</button>
    </mat-dialog-actions>`,
})
export class ConfirmDialogComponent {
  readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
  readonly ref = inject<MatDialogRef<ConfirmDialogComponent, ConfirmResult>>(MatDialogRef);
  readonly reason = signal('');
}
