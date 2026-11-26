import { describe, expect, it } from 'vitest';
import { redactSecretsInObject, redactSecretsInString } from '../../src/domain/secretRedactor.js';

describe('secretRedactor', () => {
  it('redacts GitHub tokens, Bearer headers, OpenAI keys, and confirmation tokens in strings', () => {
    const raw =
      'Failed with Authorization: Bearer supersecretvalue123 and ghp_abcdefghijklmnopqrstuvwxyz123456 and github_pat_11ABCDEF1234567890_xyz and sk-proj1234567890abcdefghij';
    const redacted = redactSecretsInString(raw);
    expect(redacted).not.toContain('supersecretvalue123');
    expect(redacted).not.toContain('ghp_');
    expect(redacted).not.toContain('github_pat_');
    expect(redacted).not.toContain('sk-proj');
    expect(redacted).toContain('[REDACTED]');
  });

  it('recursively redacts sensitive object keys while preserving safe fields', () => {
    const sanitized = redactSecretsInObject({
      owner: 'acme',
      repo: 'app',
      githubToken: 'ghp_123456789012345678901234567890',
      confirmationToken:
        'conf_v1.0123456789abcdef0123456789abcdef.0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
      nested: {
        authorization: 'Bearer topsecret12345',
        safeNote: 'keep me',
      },
    }) as Record<string, unknown>;

    expect(sanitized.owner).toBe('acme');
    expect(sanitized.githubToken).toBe('[REDACTED]');
    expect(sanitized.confirmationToken).toBe('[REDACTED_CONFIRMATION_TOKEN]');
    expect((sanitized.nested as Record<string, unknown>).authorization).toBe('[REDACTED]');
    expect((sanitized.nested as Record<string, unknown>).safeNote).toBe('keep me');
  });
});
