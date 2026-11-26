import { DestroyRef, Signal, WritableSignal, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ProblemDetail } from './models';

export interface Loadable<T> {
  readonly data: Signal<T | null>;
  readonly loading: Signal<boolean>;
  readonly error: Signal<string | null>;
  reload(): void;
}

export function describeError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const problem = error.error as ProblemDetail | null;
    if (error.status === 0) return 'Backend unreachable';
    const detail = problem?.detail ?? problem?.title ?? error.statusText;
    return `${error.status}: ${detail}${problem?.requestId ? ` (request ${problem.requestId})` : ''}`;
  }
  return 'Unexpected error';
}

/** Loads once and then every {@code intervalMs} (0 = once). Must be called in an injection context. */
export function poll<T>(source: () => Observable<T>, intervalMs = 0): Loadable<T> {
  const data: WritableSignal<T | null> = signal(null);
  const loading = signal(true);
  const error = signal<string | null>(null);
  const destroyRef = inject(DestroyRef);
  let inFlight = false;

  const load = () => {
    if (inFlight) return;
    inFlight = true;
    source().subscribe({
      next: (value) => {
        data.set(value);
        error.set(null);
        loading.set(false);
        inFlight = false;
      },
      error: (e: unknown) => {
        error.set(describeError(e));
        loading.set(false);
        inFlight = false;
      },
    });
  };
  load();
  if (intervalMs > 0) {
    const timer = setInterval(load, intervalMs);
    destroyRef.onDestroy(() => clearInterval(timer));
  }
  return { data, loading, error, reload: load };
}
