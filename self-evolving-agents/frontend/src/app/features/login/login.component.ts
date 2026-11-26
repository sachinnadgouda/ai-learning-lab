import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButton } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { describeError } from '../../core/poller';

@Component({
  selector: 'app-login',
  imports: [FormsModule, MatCardModule, MatFormFieldModule, MatInput, MatButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `:host { display: flex; justify-content: center; padding-top: 12vh; } mat-card { width: 420px; padding: 16px; }`,
  template: `
    <mat-card>
      <mat-card-header><mat-card-title>Administrator sign-in</mat-card-title></mat-card-header>
      <mat-card-content>
        <p class="muted">Enter the admin token configured as <code>DEMO_ADMIN_TOKEN</code>. It is kept in this tab only (sessionStorage).</p>
        <form (ngSubmit)="submit()">
          <mat-form-field appearance="outline" style="width:100%">
            <mat-label>Admin token</mat-label>
            <input matInput type="password" name="token" autocomplete="off" [ngModel]="token()" (ngModelChange)="token.set($event)" required />
          </mat-form-field>
          @if (error(); as e) { <p role="alert" style="color:var(--mat-sys-error)">{{ e }}</p> }
          <button mat-flat-button type="submit" [disabled]="!token().trim() || busy()">Sign in</button>
        </form>
      </mat-card-content>
    </mat-card>`,
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly token = signal('');
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  submit(): void {
    this.busy.set(true);
    this.error.set(null);
    this.auth.login(this.token());
    this.api.flags().subscribe({
      next: () => void this.router.navigate(['/overview']),
      error: (e: unknown) => {
        this.auth.logout();
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }
}
