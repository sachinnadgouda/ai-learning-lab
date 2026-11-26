import { describe, expect, it } from 'vitest';
import { AppError } from '../../src/domain/errors.js';
import { CircuitBreaker } from '../../src/infrastructure/github/circuitBreaker.js';

describe('CircuitBreaker', () => {
  it('transitions CLOSED -> OPEN -> HALF_OPEN -> CLOSED and re-opens on probe failure', () => {
    let now = 1_000;
    const breaker = new CircuitBreaker({
      failureThreshold: 3,
      resetTimeoutMs: 5_000,
      clock: () => now,
    });

    expect(breaker.getState()).toBe('CLOSED');
    breaker.recordFailure();
    breaker.recordFailure();
    expect(breaker.getState()).toBe('CLOSED');
    breaker.recordFailure();
    expect(breaker.getState()).toBe('OPEN');

    try {
      breaker.beforeRequest('github');
      expect.unreachable('Expected CIRCUIT_OPEN');
    } catch (err) {
      expect(err).toBeInstanceOf(AppError);
      expect((err as AppError).code).toBe('CIRCUIT_OPEN');
    }

    // Advance past cooldown -> HALF_OPEN
    now += 5_001;
    expect(breaker.getState()).toBe('HALF_OPEN');
    expect(() => breaker.beforeRequest('github')).not.toThrow();

    // Probe failure immediately re-opens
    breaker.recordFailure();
    expect(breaker.getState()).toBe('OPEN');

    // Advance again and succeed -> CLOSED
    now += 5_001;
    expect(breaker.getState()).toBe('HALF_OPEN');
    breaker.recordSuccess();
    expect(breaker.getState()).toBe('CLOSED');
    expect(breaker.getConsecutiveFailures()).toBe(0);
  });
});
