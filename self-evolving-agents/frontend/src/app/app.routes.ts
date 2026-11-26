import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent) },
  {
    path: '',
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'overview' },
      { path: 'overview', title: 'Overview', loadComponent: () => import('./features/overview/overview.component').then((m) => m.OverviewComponent) },
      { path: 'ai-inspector', title: 'AI Inspector', loadComponent: () => import('./features/ai-inspector/ai-inspector.component').then((m) => m.AiInspectorComponent) },
      { path: 'timeline', title: 'Evolution Timeline', loadComponent: () => import('./features/timeline/timeline.component').then((m) => m.TimelineComponent) },
      { path: 'policies', title: 'Policies', loadComponent: () => import('./features/policies/policies.component').then((m) => m.PoliciesComponent) },
      { path: 'experiments', title: 'Experiments', loadComponent: () => import('./features/experiments/experiments.component').then((m) => m.ExperimentsComponent) },
      { path: 'settings', title: 'Settings', loadComponent: () => import('./features/settings/settings.component').then((m) => m.SettingsComponent) },
      { path: 'logs', title: 'Logs & Diagnostics', loadComponent: () => import('./features/logs/logs.component').then((m) => m.LogsComponent) },
    ],
  },
  { path: '**', redirectTo: '' },
];
