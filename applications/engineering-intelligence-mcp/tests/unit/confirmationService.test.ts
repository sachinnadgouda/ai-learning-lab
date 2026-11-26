import { afterEach, describe, expect, it } from 'vitest';
import { z } from 'zod';
import { ToolExecutionOrchestrator } from '../../src/application/toolExecutionOrchestrator.js';
import { ConfirmationService } from '../../src/domain/confirmationService.js';
import { DeploymentMode } from '../../src/domain/entities.js';
import { AppError } from '../../src/domain/errors.js';
import { PermissionPolicy } from '../../src/domain/permissionPolicy.js';
import { SqliteAuditStore } from '../../src/infrastructure/audit/SqliteAuditStore.js';
import { loadConfig } from '../../src/infrastructure/config/env.js';
import { JsonStderrLogger } from '../../src/infrastructure/observability/logger.js';
import { InMemoryMetrics } from '../../src/infrastructure/observability/metrics.js';

describe('ConfirmationService (two-phase confirmation & trust boundary)', () => {
  const stores: SqliteAuditStore[] = [];

  afterEach(() => {
    while (stores.length > 0) {
      stores.pop()?.close();
    }
  });

  function createFixture(options?: {
    initialNowMs?: number;
    deploymentMode?: DeploymentMode;
  }) {
    let nowMs = options?.initialNowMs ?? Date.parse('2026-11-26T12:00:00Z');
    const store = new SqliteAuditStore(':memory:');
    stores.push(store);
    const service = new ConfirmationService({
      secret: 'unit-test-hmac-secret-at-least-32-chars',
      ttlSeconds: 120,
      store,
      deploymentMode: options?.deploymentMode ?? 'local-stdio',
      clock: () => new Date(nowMs),
    });
    return {
      service,
      store,
      advanceSeconds: (sec: number) => {
        nowMs += sec * 1000;
      },
    };
  }

  it('issues a challenge when token is omitted in local-stdio mode with explicit guardrail notice, executes once, and rejects replay', () => {
    const { service } = createFixture({ deploymentMode: 'local-stdio' });
    const args = { owner: 'acme', repo: 'app', issueNumber: 5, body: 'Looks good' };

    let issuedToken = '';
    try {
      service.requireOrConsume({
        toolName: 'write_create_issue_comment',
        actor: 'alice',
        sessionId: 'local-session-1',
        repository: 'acme/app',
        args,
        actionPreview: 'Comment on acme/app#5',
      });
      expect.unreachable('Expected CONFIRMATION_REQUIRED');
    } catch (err) {
      expect(err).toBeInstanceOf(AppError);
      const appErr = err as AppError;
      expect(appErr.code).toBe('CONFIRMATION_REQUIRED');
      expect(appErr.details?.trustModel).toBe('local-single-user-guardrail');
      expect(appErr.details?.independentHumanAuth).toBe(false);
      expect(String(appErr.details?.trustNotice)).toContain('does NOT prove independent human authentication');
      issuedToken = String(appErr.details?.confirmationToken ?? '');
      expect(issuedToken).toMatch(/^conf_v2\.[a-f0-9]{32}\.[a-f0-9]{64}$/);
    }

    // Valid consumption succeeds within same session & actor
    const verified = service.requireOrConsume({
      toolName: 'write_create_issue_comment',
      actor: 'alice',
      sessionId: 'local-session-1',
      repository: 'acme/app',
      args: { ...args, confirmationToken: issuedToken },
      actionPreview: 'Comment on acme/app#5',
      confirmationToken: issuedToken,
    });
    expect(verified.confirmed).toBe(true);

    // Replay with same token MUST fail
    try {
      service.requireOrConsume({
        toolName: 'write_create_issue_comment',
        actor: 'alice',
        sessionId: 'local-session-1',
        repository: 'acme/app',
        args,
        actionPreview: 'Comment on acme/app#5',
        confirmationToken: issuedToken,
      });
      expect.unreachable('Expected replay rejection');
    } catch (err) {
      expect((err as AppError).code).toBe('PERMISSION_DENIED');
      expect((err as AppError).message).toContain('replay');
    }
  });

  it('rejects tampered arguments, wrong actor, wrong session, malformed token, and expired token', () => {
    const { service, advanceSeconds } = createFixture({ deploymentMode: 'local-stdio' });
    const originalArgs = { owner: 'acme', repo: 'app', title: 'Legit bug', body: 'Details' };

    let token = '';
    try {
      service.requireOrConsume({
        toolName: 'write_create_issue',
        actor: 'alice',
        sessionId: 'sess-A',
        repository: 'acme/app',
        args: originalArgs,
        actionPreview: 'Create issue',
      });
    } catch (err) {
      token = String((err as AppError).details?.confirmationToken);
    }

    // Tampered arguments rejected
    expect(() =>
      service.requireOrConsume({
        toolName: 'write_create_issue',
        actor: 'alice',
        sessionId: 'sess-A',
        repository: 'acme/app',
        args: { ...originalArgs, body: 'Tampered payload!' },
        actionPreview: 'Create issue',
        confirmationToken: token,
      }),
    ).toThrowError(/signature|bound context/i);

    // Different actor rejected
    expect(() =>
      service.requireOrConsume({
        toolName: 'write_create_issue',
        actor: 'mallory',
        sessionId: 'sess-A',
        repository: 'acme/app',
        args: originalArgs,
        actionPreview: 'Create issue',
        confirmationToken: token,
      }),
    ).toThrowError(AppError);

    // Different session rejected even for same actor
    expect(() =>
      service.requireOrConsume({
        toolName: 'write_create_issue',
        actor: 'alice',
        sessionId: 'sess-B',
        repository: 'acme/app',
        args: originalArgs,
        actionPreview: 'Create issue',
        confirmationToken: token,
      }),
    ).toThrowError(AppError);

    // Malformed token rejected
    expect(() =>
      service.requireOrConsume({
        toolName: 'write_create_issue',
        actor: 'alice',
        sessionId: 'sess-A',
        repository: 'acme/app',
        args: originalArgs,
        actionPreview: 'Create issue',
        confirmationToken: 'not-a-real-token',
      }),
    ).toThrowError(AppError);

    // Expire past TTL (120s)
    advanceSeconds(125);
    expect(() =>
      service.requireOrConsume({
        toolName: 'write_create_issue',
        actor: 'alice',
        sessionId: 'sess-A',
        repository: 'acme/app',
        args: originalArgs,
        actionPreview: 'Create issue',
        confirmationToken: token,
      }),
    ).toThrowError(/expired/i);
  });

  it('in shared-server mode, refuses unauthenticated/static identity and binds confirmation strictly to authenticated principal + session', async () => {
    // Startup config refuses ephemeral or short CONFIRMATION_SECRET in shared-server mode
    expect(() =>
      loadConfig({
        DEPLOYMENT_MODE: 'shared-server',
        GITHUB_MODE: 'demo',
        ALLOWED_REPOS: 'acme/app',
        CONFIRMATION_SECRET: '',
      }),
    ).toThrowError(/CONFIRMATION_SECRET \(min 32 chars\) is required/i);

    const { service, store } = createFixture({ deploymentMode: 'shared-server' });
    const policy = new PermissionPolicy({
      mode: 'write-with-confirmation',
      allowedRepos: ['acme/app'],
      actor: 'static-env-actor',
    });
    const orchestrator = new ToolExecutionOrchestrator({
      permissionPolicy: policy,
      confirmationService: service,
      auditStore: store,
      logger: new JsonStderrLogger('error', () => {}),
      metrics: new InMemoryMetrics(),
    });

    const schema = z.object({
      owner: z.string(),
      repo: z.string(),
      body: z.string(),
      confirmationToken: z.string().optional(),
    });

    let executedCount = 0;
    const writeDescriptor = {
      toolName: 'write_create_issue_comment',
      permissionClass: 'WRITE' as const,
      schema,
      extractRepo: (i: z.infer<typeof schema>) => ({ owner: i.owner, repo: i.repo }),
      execute: async () => {
        executedCount += 1;
        return { ok: true };
      },
    };

    // 1. Calling without authenticated callerIdentity (relying on static ACTOR_ID) is rejected with AUTH_FAILED
    const unauthRes = await orchestrator.execute(writeDescriptor, {
      owner: 'acme',
      repo: 'app',
      body: 'hello',
    });
    expect(unauthRes.ok).toBe(false);
    expect(unauthRes.error?.code).toBe('AUTH_FAILED');
    expect(executedCount).toBe(0);

    // 2. Authenticated principal Alice in session-1 receives challenge bound to (alice@corp, sess-1)
    const aliceSession1 = {
      principalId: 'alice@corp',
      sessionId: 'sess-1',
      authenticated: true,
      authMethod: 'authenticated-principal' as const,
    };
    const challengeRes = await orchestrator.execute(
      writeDescriptor,
      { owner: 'acme', repo: 'app', body: 'hello' },
      { callerIdentity: aliceSession1 },
    );
    expect(challengeRes.ok).toBe(false);
    expect(challengeRes.error?.code).toBe('CONFIRMATION_REQUIRED');
    expect(challengeRes.error?.details?.trustModel).toBe('shared-authenticated-principal-session');
    const token = String(challengeRes.error?.details?.confirmationToken ?? '');
    expect(token).toMatch(/^conf_v2\./);

    // 3. Stolen token used by Bob (different principal) -> PERMISSION_DENIED
    const bobRes = await orchestrator.execute(
      writeDescriptor,
      { owner: 'acme', repo: 'app', body: 'hello', confirmationToken: token },
      {
        callerIdentity: {
          principalId: 'bob@corp',
          sessionId: 'sess-1',
          authenticated: true,
          authMethod: 'authenticated-principal',
        },
      },
    );
    expect(bobRes.ok).toBe(false);
    expect(bobRes.error?.code).toBe('PERMISSION_DENIED');
    expect(executedCount).toBe(0);

    // 4. Stolen token used by Alice in a different session (sess-2) -> PERMISSION_DENIED
    const aliceOtherSessionRes = await orchestrator.execute(
      writeDescriptor,
      { owner: 'acme', repo: 'app', body: 'hello', confirmationToken: token },
      {
        callerIdentity: {
          principalId: 'alice@corp',
          sessionId: 'sess-2',
          authenticated: true,
          authMethod: 'authenticated-principal',
        },
      },
    );
    expect(aliceOtherSessionRes.ok).toBe(false);
    expect(aliceOtherSessionRes.error?.code).toBe('PERMISSION_DENIED');
    expect(executedCount).toBe(0);

    // 5. Same authenticated principal + same session succeeds
    const validRes = await orchestrator.execute(
      writeDescriptor,
      { owner: 'acme', repo: 'app', body: 'hello', confirmationToken: token },
      { callerIdentity: aliceSession1 },
    );
    expect(validRes.ok).toBe(true);
    expect(executedCount).toBe(1);
  });
});
