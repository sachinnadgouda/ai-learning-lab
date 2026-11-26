import { AppError } from '../../domain/errors.js';

export type CircuitState = 'CLOSED' | 'OPEN' | 'HALF_OPEN';

export interface CircuitBreakerOptions {
  failureThreshold: number;
  resetTimeoutMs: number;
  clock?: () => number;
}

export class CircuitBreaker {
  private state: CircuitState = 'CLOSED';
  private consecutiveFailures = 0;
  private openedAtMs: number | null = null;
  private readonly failureThreshold: number;
  private readonly resetTimeoutMs: number;
  private readonly clock: () => number;

  constructor(options: CircuitBreakerOptions) {
    this.failureThreshold = options.failureThreshold;
    this.resetTimeoutMs = options.resetTimeoutMs;
    this.clock = options.clock ?? (() => Date.now());
  }

  public getState(): CircuitState {
    if (
      this.state === 'OPEN' &&
      this.openedAtMs !== null &&
      this.clock() - this.openedAtMs >= this.resetTimeoutMs
    ) {
      this.state = 'HALF_OPEN';
    }
    return this.state;
  }

  public getConsecutiveFailures(): number {
    return this.consecutiveFailures;
  }

  public beforeRequest(upstreamName: string): void {
    const current = this.getState();
    if (current === 'OPEN') {
      const remainingMs = Math.max(
        0,
        this.resetTimeoutMs - (this.clock() - (this.openedAtMs ?? this.clock())),
      );
      throw new AppError(
        'CIRCUIT_OPEN',
        `Upstream '${upstreamName}' circuit breaker is OPEN due to repeated failures. Retry after ${Math.ceil(remainingMs / 1000)}s.`,
        {
          retryable: true,
          details: {
            upstream: upstreamName,
            state: 'OPEN',
            retryAfterMs: remainingMs,
          },
        },
      );
    }
  }

  public recordSuccess(): void {
    this.consecutiveFailures = 0;
    this.openedAtMs = null;
    this.state = 'CLOSED';
  }

  public recordFailure(): void {
    this.consecutiveFailures += 1;
    if (this.state === 'HALF_OPEN' || this.consecutiveFailures >= this.failureThreshold) {
      this.state = 'OPEN';
      this.openedAtMs = this.clock();
    }
  }
}
