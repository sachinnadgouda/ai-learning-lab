import { Injectable, computed, signal } from '@angular/core';

const KEY = 'demo.adminToken';

/** Holds the admin token for the browser tab only (sessionStorage), never in localStorage. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _token = signal<string | null>(this.read());
  readonly token = this._token.asReadonly();
  readonly authenticated = computed(() => !!this._token());

  login(token: string): void {
    const value = token.trim();
    sessionStorage.setItem(KEY, value);
    this._token.set(value);
  }

  logout(): void {
    sessionStorage.removeItem(KEY);
    this._token.set(null);
  }

  private read(): string | null {
    try {
      return sessionStorage.getItem(KEY);
    } catch {
      return null;
    }
  }
}
