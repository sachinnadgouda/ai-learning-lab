import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbar } from '@angular/material/toolbar';
import { AuthService } from './core/auth.service';

interface NavItem { path: string; label: string; icon: string }

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatSidenavModule, MatListModule, MatToolbar, MatIcon, MatButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .shell { height: 100vh; } mat-sidenav { width: 230px; }
    main { padding: 16px 24px; }
    .spacer { flex: 1; }
  `,
  template: `
    @if (auth.authenticated()) {
      <mat-sidenav-container class="shell">
        <mat-sidenav mode="side" opened>
          <mat-toolbar>Traffic Evolution</mat-toolbar>
          <mat-nav-list>
            @for (item of nav; track item.path) {
              <a mat-list-item [routerLink]="item.path" routerLinkActive="active" #rla="routerLinkActive" [activated]="rla.isActive">
                <mat-icon matListItemIcon>{{ item.icon }}</mat-icon>
                <span matListItemTitle>{{ item.label }}</span>
              </a>
            }
          </mat-nav-list>
        </mat-sidenav>
        <mat-sidenav-content>
          <mat-toolbar>
            <span class="spacer"></span>
            <button mat-button type="button" (click)="logout()"><mat-icon>logout</mat-icon> Sign out</button>
          </mat-toolbar>
          <main><router-outlet /></main>
        </mat-sidenav-content>
      </mat-sidenav-container>
    } @else {
      <router-outlet />
    }
  `,
})
export class App {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly nav: NavItem[] = [
    { path: '/overview', label: 'Overview', icon: 'dashboard' },
    { path: '/ai-inspector', label: 'AI Inspector', icon: 'psychology' },
    { path: '/timeline', label: 'Evolution Timeline', icon: 'timeline' },
    { path: '/policies', label: 'Policies', icon: 'policy' },
    { path: '/experiments', label: 'Experiments', icon: 'science' },
    { path: '/settings', label: 'Settings', icon: 'settings' },
    { path: '/logs', label: 'Logs & Diagnostics', icon: 'list_alt' },
  ];

  protected logout(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
