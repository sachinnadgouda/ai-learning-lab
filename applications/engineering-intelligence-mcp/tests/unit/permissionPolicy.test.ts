import { describe, expect, it } from 'vitest';
import { PermissionPolicy, parseAllowedRepoEntry } from '../../src/domain/permissionPolicy.js';
import { AppError } from '../../src/domain/errors.js';
import { loadConfig } from '../../src/infrastructure/config/env.js';

describe('PermissionPolicy & ALLOWED_REPOS Configuration', () => {
  it('enforces secure default-deny when ALLOWED_REPOS is empty', () => {
    const policy = new PermissionPolicy({
      mode: 'read-only',
      allowedRepos: [],
      actor: 'dev',
    });

    expect(policy.isRepoAllowed('acme-eng', 'payments-platform')).toBe(false);

    try {
      policy.assertAllowed({
        toolName: 'analyze_pull_request',
        permissionClass: 'READ',
        owner: 'acme-eng',
        repo: 'payments-platform',
      });
      expect.unreachable('Expected default-deny PERMISSION_DENIED');
    } catch (err) {
      expect(err).toBeInstanceOf(AppError);
      const appErr = err as AppError;
      expect(appErr.code).toBe('PERMISSION_DENIED');
      expect(appErr.details?.defaultDeny).toBe(true);
    }
  });

  it('allows explicit allowlist entries, normalizes case/whitespace, and denies unlisted repos', () => {
    const policy = new PermissionPolicy({
      mode: 'write-with-confirmation',
      allowedRepos: ['  Acme-Eng/Payments-Platform  '],
      actor: 'dev',
    });

    expect(policy.getAllowedRepos()).toEqual(['acme-eng/payments-platform']);

    expect(() =>
      policy.assertAllowed({
        toolName: 'write_create_issue',
        permissionClass: 'WRITE',
        owner: 'ACME-ENG',
        repo: 'PAYMENTS-PLATFORM',
      }),
    ).not.toThrow();

    try {
      policy.assertAllowed({
        toolName: 'analyze_pull_request',
        permissionClass: 'READ',
        owner: 'other-org',
        repo: 'secret-repo',
      });
      expect.unreachable('Expected PERMISSION_DENIED');
    } catch (err) {
      expect(err).toBeInstanceOf(AppError);
      expect((err as AppError).code).toBe('PERMISSION_DENIED');
      expect((err as AppError).details?.defaultDeny).toBe(false);
    }
  });

  it('blocks WRITE tools in read-only mode even when repository is allowlisted', () => {
    const policy = new PermissionPolicy({
      mode: 'read-only',
      allowedRepos: ['acme/service'],
      actor: 'dev',
    });

    expect(() =>
      policy.assertAllowed({
        toolName: 'analyze_pull_request',
        permissionClass: 'READ',
        owner: 'acme',
        repo: 'service',
      }),
    ).not.toThrow();

    expect(() =>
      policy.assertAllowed({
        toolName: 'write_create_issue',
        permissionClass: 'WRITE',
        owner: 'acme',
        repo: 'service',
      }),
    ).toThrowError(AppError);
  });

  it('supports org-scoped wildcards (owner/*) and rejects global or partial wildcards', () => {
    const policy = new PermissionPolicy({
      mode: 'read-only',
      allowedRepos: ['Acme-Eng/*'],
      actor: 'dev',
    });

    expect(policy.isRepoAllowed('acme-eng', 'payments-platform')).toBe(true);
    expect(policy.isRepoAllowed('ACME-ENG', 'billing-api')).toBe(true);
    expect(policy.isRepoAllowed('other-org', 'payments-platform')).toBe(false);

    for (const badWildcard of ['*', '*/*', '*/payments-platform', 'acme*/repo', 'acme/pay*', 'acme/**']) {
      expect(() => parseAllowedRepoEntry(badWildcard)).toThrowError(AppError);
      expect(
        () =>
          new PermissionPolicy({
            mode: 'read-only',
            allowedRepos: [badWildcard],
            actor: 'dev',
          }),
      ).toThrowError(AppError);
    }
  });

  it('rejects malformed ALLOWED_REPOS entries in both policy and loadConfig', () => {
    for (const malformed of ['just-a-repo', 'a/b/c', '/repo', 'owner/', 'acme/..', 'acme/bad space', '']) {
      expect(() => parseAllowedRepoEntry(malformed)).toThrowError(AppError);
    }

    expect(() =>
      loadConfig({
        GITHUB_MODE: 'demo',
        ALLOWED_REPOS: 'acme/ok, ,acme/also-ok',
      }),
    ).toThrowError(/empty repository entry|Malformed/i);

    expect(() =>
      loadConfig({
        GITHUB_MODE: 'demo',
        ALLOWED_REPOS: '*/*',
      }),
    ).toThrowError(/Global wildcard/i);
  });

  it('isolates explicit unrestricted demo mode and rejects it in live or shared-server configs', () => {
    // Explicit opt-in in demo + local-stdio succeeds
    const demoConfig = loadConfig({
      GITHUB_MODE: 'demo',
      DEPLOYMENT_MODE: 'local-stdio',
      ALLOW_UNRESTRICTED_DEMO_REPOS: 'true',
      ALLOWED_REPOS: '',
    });
    expect(demoConfig.allowUnrestrictedDemoRepos).toBe(true);

    const demoPolicy = new PermissionPolicy({
      mode: 'read-only',
      allowedRepos: demoConfig.allowedRepos,
      allowUnrestrictedDemoRepos: demoConfig.allowUnrestrictedDemoRepos,
      actor: 'dev',
    });
    expect(demoPolicy.isRepoAllowed('any-org', 'any-repo')).toBe(true);

    // Mixing allowUnrestrictedDemoRepos with explicit ALLOWED_REPOS is rejected
    expect(() =>
      loadConfig({
        GITHUB_MODE: 'demo',
        ALLOW_UNRESTRICTED_DEMO_REPOS: 'true',
        ALLOWED_REPOS: 'acme-eng/payments-platform',
      }),
    ).toThrowError(/cannot be combined/i);

    // Live mode with empty ALLOWED_REPOS fails fast at startup
    expect(() =>
      loadConfig({
        GITHUB_MODE: 'live',
        GITHUB_TOKEN: 'ghp_example_token_1234567890',
        ALLOWED_REPOS: '',
      }),
    ).toThrowError(/ALLOWED_REPOS must specify at least one/i);

    // Live mode with ALLOW_UNRESTRICTED_DEMO_REPOS=true fails fast at startup
    expect(() =>
      loadConfig({
        GITHUB_MODE: 'live',
        GITHUB_TOKEN: 'ghp_example_token_1234567890',
        ALLOW_UNRESTRICTED_DEMO_REPOS: 'true',
      }),
    ).toThrowError(/ALLOW_UNRESTRICTED_DEMO_REPOS=true is forbidden/i);

    // Shared-server mode with ALLOW_UNRESTRICTED_DEMO_REPOS=true fails fast at startup
    expect(() =>
      loadConfig({
        DEPLOYMENT_MODE: 'shared-server',
        GITHUB_MODE: 'demo',
        ALLOW_UNRESTRICTED_DEMO_REPOS: 'true',
        CONFIRMATION_SECRET: 'a-strong-shared-server-secret-value-at-least-32-chars',
      }),
    ).toThrowError(/forbidden in shared-server/i);
  });
});
