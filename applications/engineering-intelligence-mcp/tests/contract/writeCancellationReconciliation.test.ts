import { createServer, IncomingMessage, Server, ServerResponse } from 'node:http';
import { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import {
  CreateIssueCommentInputSchema,
  ReconcileWriteOutcomeInputSchema,
} from '../../src/application/schemas.js';
import { ToolExecutionOrchestrator } from '../../src/application/toolExecutionOrchestrator.js';
import { CreateIssueCommentUseCase } from '../../src/application/usecases/createIssueComment.js';
import { ReconcileWriteOutcomeUseCase } from '../../src/application/usecases/reconcileWriteOutcome.js';
import { ConfirmationService } from '../../src/domain/confirmationService.js';
import { PermissionPolicy } from '../../src/domain/permissionPolicy.js';
import { SqliteAuditStore } from '../../src/infrastructure/audit/SqliteAuditStore.js';
import { loadConfig } from '../../src/infrastructure/config/env.js';
import { GitHubRestClient } from '../../src/infrastructure/github/GitHubRestClient.js';
import { JsonStderrLogger } from '../../src/infrastructure/observability/logger.js';
import { InMemoryMetrics } from '../../src/infrastructure/observability/metrics.js';

describe('Write Cancellation, Ambiguous Write Outcomes, and Reconciliation', () => {
  const servers: Server[] = [];
  const stores: SqliteAuditStore[] = [];

  afterEach(async () => {
    while (servers.length > 0) {
      const srv = servers.pop();
      if (srv) {
        await new Promise<void>((resolve) => srv.close(() => resolve()));
      }
    }
    while (stores.length > 0) {
      stores.pop()?.close();
    }
  });

  async function startMockGitHub(
    handler: (req: IncomingMessage, res: ServerResponse) => void,
  ): Promise<string> {
    const server = createServer(handler);
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
    const address = server.address() as AddressInfo;
    return `http://127.0.0.1:${address.port}`;
  }

  function buildStack(baseUrl: string, toolTimeoutMs = 150) {
    const store = new SqliteAuditStore(':memory:');
    stores.push(store);
    const logger = new JsonStderrLogger('error', () => {});
    const metrics = new InMemoryMetrics();

    const github = new GitHubRestClient({
      baseUrl,
      token: 'ghp_test_token_reconciliation',
      timeoutMs: 2_000,
      maxRetries: 3,
      maxPages: 2,
      baseBackoffMs: 10,
      logger,
      metrics,
    });

    const permissionPolicy = new PermissionPolicy({
      mode: 'write-with-confirmation',
      allowedRepos: ['acme/app'],
      actor: 'reconcile-tester',
    });

    const confirmationService = new ConfirmationService({
      secret: 'reconciliation-test-hmac-secret-32-chars!!',
      ttlSeconds: 300,
      store,
      deploymentMode: 'local-stdio',
    });

    const orchestrator = new ToolExecutionOrchestrator({
      permissionPolicy,
      confirmationService,
      auditStore: store,
      reconciliationStore: store,
      logger,
      metrics,
      defaultTimeoutMs: toolTimeoutMs,
    });

    const createCommentUseCase = new CreateIssueCommentUseCase(github);
    const reconcileUseCase = new ReconcileWriteOutcomeUseCase(github, store, permissionPolicy);

    const writeCommentDescriptor = {
      toolName: 'write_create_issue_comment',
      permissionClass: 'WRITE' as const,
      schema: CreateIssueCommentInputSchema,
      timeoutMs: toolTimeoutMs,
      extractRepo: (i: { owner: string; repo: string }) => ({ owner: i.owner, repo: i.repo }),
      buildReconciliationSeed: (i: { issueNumber: number }) => ({
        toolName: 'write_create_issue_comment' as const,
        targetNumber: i.issueNumber,
      }),
      execute: (
        i: { owner: string; repo: string; issueNumber: number; body: string },
        ctx: { correlationId: string; signal: AbortSignal },
      ) =>
        createCommentUseCase.execute(i, {
          signal: ctx.signal,
          correlationId: ctx.correlationId,
        }),
    };

    const reconcileDescriptor = {
      toolName: 'read_reconcile_write_outcome',
      permissionClass: 'READ' as const,
      schema: ReconcileWriteOutcomeInputSchema,
      execute: (i: { correlationId: string }, ctx: { signal: AbortSignal }) =>
        reconcileUseCase.execute(i, { signal: ctx.signal }),
    };

    return { store, orchestrator, writeCommentDescriptor, reconcileDescriptor };
  }

  async function obtainConfirmationToken(
    orchestrator: ToolExecutionOrchestrator,
    descriptor: ReturnType<typeof buildStack>['writeCommentDescriptor'],
    args: { owner: string; repo: string; issueNumber: number; body: string },
  ): Promise<string> {
    const challenge = await orchestrator.execute(descriptor, args);
    expect(challenge.ok).toBe(false);
    expect(challenge.error?.code).toBe('CONFIRMATION_REQUIRED');
    return String(challenge.error?.details?.confirmationToken ?? '');
  }

  it('Scenario 1: timeout/cancellation BEFORE request leaves process returns CANCELLED with zero upstream calls and is NOT ambiguous', async () => {
    let postRequests = 0;
    const baseUrl = await startMockGitHub((req, res) => {
      if (req.method === 'POST') {
        postRequests += 1;
      }
      res.writeHead(201, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ id: 1, body: 'ok', user: { login: 'bot' }, created_at: '', html_url: '' }));
    });

    const { orchestrator, store, writeCommentDescriptor } = buildStack(baseUrl, 500);
    const args = { owner: 'acme', repo: 'app', issueNumber: 42, body: 'Pre-cancelled write' };
    const token = await obtainConfirmationToken(orchestrator, writeCommentDescriptor, args);

    // Pre-aborted caller signal simulating timeout before transmission
    const preAborted = new AbortController();
    preAborted.abort();

    const result = await orchestrator.execute(
      writeCommentDescriptor,
      { ...args, confirmationToken: token },
      { signal: preAborted.signal },
    );

    expect(result.ok).toBe(false);
    expect(result.error?.code).toBe('CANCELLED');
    expect(result.error?.retryable).toBe(false);
    expect(result.error?.details?.phase).toBe('before_send');
    expect(result.error?.details?.sideEffectPossible).toBe(false);
    expect(postRequests).toBe(0);
    expect(store.listPendingWriteReconciliations()).toHaveLength(0);
  });

  it('Scenario 2: timeout DURING in-flight write aborts upstream request, never retries, returns AMBIGUOUS_WRITE_OUTCOME, and reconciles as NOT_FOUND_UPSTREAM', async () => {
    let postRequests = 0;
    let serverObservedAbort = false;
    const storedComments: Array<{ id: number; body: string; user: { login: string }; created_at: string; html_url: string }> = [];

    const baseUrl = await startMockGitHub((req, res) => {
      if (req.method === 'POST' && req.url?.includes('/issues/42/comments')) {
        postRequests += 1;
        req.on('close', () => {
          if (!res.writableEnded) {
            serverObservedAbort = true;
          }
        });
        // Hang longer than orchestrator timeout (120ms) without committing mutation
        setTimeout(() => {
          if (!res.writableEnded && !res.destroyed) {
            res.writeHead(201, { 'content-type': 'application/json' });
            res.end(JSON.stringify({ id: 99, body: 'too late' }));
          }
        }, 1_500).unref();
        return;
      }

      if (req.method === 'GET' && req.url?.includes('/issues/42/comments')) {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(storedComments));
        return;
      }

      res.writeHead(404);
      res.end();
    });

    const { orchestrator, store, writeCommentDescriptor, reconcileDescriptor } = buildStack(baseUrl, 120);
    const args = { owner: 'acme', repo: 'app', issueNumber: 42, body: 'In-flight timeout test' };
    const token = await obtainConfirmationToken(orchestrator, writeCommentDescriptor, args);

    const writeRes = await orchestrator.execute(writeCommentDescriptor, {
      ...args,
      confirmationToken: token,
    });

    // Wait briefly so socket close propagates to mock server
    await new Promise((r) => setTimeout(r, 40));

    expect(writeRes.ok).toBe(false);
    expect(writeRes.error?.code).toBe('AMBIGUOUS_WRITE_OUTCOME');
    expect(writeRes.error?.retryable).toBe(false);
    expect(writeRes.error?.details?.doNotBlindlyRetry).toBe(true);
    expect(writeRes.error?.details?.reconciliationRecorded).toBe(true);
    expect(writeRes.error?.details?.reconcileTool).toBe('read_reconcile_write_outcome');
    // Must NEVER blindly retry a write!
    expect(postRequests).toBe(1);
    expect(serverObservedAbort).toBe(true);

    const pending = store.listPendingWriteReconciliations();
    expect(pending).toHaveLength(1);
    expect(pending[0]?.correlationId).toBe(writeRes.correlationId);

    // Reconcile against GitHub -> NOT_FOUND_UPSTREAM
    const reconcileRes = await orchestrator.execute(reconcileDescriptor, {
      correlationId: writeRes.correlationId,
    });
    expect(reconcileRes.ok).toBe(true);
    expect(reconcileRes.data?.reconciledStatus).toBe('NOT_FOUND_UPSTREAM');
    expect(reconcileRes.data?.appliedUpstream).toBe(false);
    expect(store.listPendingWriteReconciliations()).toHaveLength(0);
  });

  it('Scenario 3: response lost AFTER upstream mutation succeeded returns AMBIGUOUS_WRITE_OUTCOME and reconciles as CONFIRMED_APPLIED', async () => {
    let postRequests = 0;
    const storedComments: Array<{ id: number; body: string; user: { login: string }; created_at: string; html_url: string }> = [];

    const baseUrl = await startMockGitHub((req, res) => {
      if (req.method === 'POST' && req.url?.includes('/issues/42/comments')) {
        postRequests += 1;
        const chunks: Buffer[] = [];
        req.on('data', (chunk: Buffer) => chunks.push(chunk));
        req.on('end', () => {
          const parsed = JSON.parse(Buffer.concat(chunks).toString('utf-8')) as { body: string };
          // Server COMMITS the mutation on GitHub...
          storedComments.push({
            id: 777,
            body: parsed.body,
            user: { login: 'octocat' },
            created_at: new Date().toISOString(),
            html_url: 'https://github.com/acme/app/issues/42#issuecomment-777',
          });
          // ...and then TCP connection drops BEFORE the response reaches the client!
          req.socket.destroy();
        });
        return;
      }

      if (req.method === 'GET' && req.url?.includes('/issues/42/comments')) {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(storedComments));
        return;
      }

      res.writeHead(404);
      res.end();
    });

    const { orchestrator, store, writeCommentDescriptor, reconcileDescriptor } = buildStack(baseUrl, 2_000);
    const args = { owner: 'acme', repo: 'app', issueNumber: 42, body: 'Mutation committed before drop' };
    const token = await obtainConfirmationToken(orchestrator, writeCommentDescriptor, args);

    const writeRes = await orchestrator.execute(writeCommentDescriptor, {
      ...args,
      confirmationToken: token,
    });

    expect(writeRes.ok).toBe(false);
    expect(writeRes.error?.code).toBe('AMBIGUOUS_WRITE_OUTCOME');
    expect(writeRes.error?.retryable).toBe(false);
    expect(writeRes.error?.details?.doNotBlindlyRetry).toBe(true);
    // Critically: ONLY 1 POST sent, ZERO duplicate comments created by blind retry!
    expect(postRequests).toBe(1);
    expect(storedComments).toHaveLength(1);

    // Reconcile inspects GitHub, matches <!-- mcp-op:<correlationId> -->, and confirms applied!
    const reconcileRes = await orchestrator.execute(reconcileDescriptor, {
      correlationId: writeRes.correlationId,
    });
    expect(reconcileRes.ok).toBe(true);
    expect(reconcileRes.data?.reconciledStatus).toBe('CONFIRMED_APPLIED');
    expect(reconcileRes.data?.appliedUpstream).toBe(true);
    expect(reconcileRes.data?.matchedResource?.idOrNumber).toBe(777);
    expect(reconcileRes.data?.recommendedAction).toContain('Do NOT retry');

    const resolvedRecord = store.findWriteReconciliation(writeRes.correlationId);
    expect(resolvedRecord?.status).toBe('CONFIRMED_APPLIED');
  });

  it('enforces safe non-loopback /metrics configuration in shared-server mode', () => {
    expect(() =>
      loadConfig({
        DEPLOYMENT_MODE: 'shared-server',
        GITHUB_MODE: 'demo',
        ALLOWED_REPOS: 'acme/app',
        CONFIRMATION_SECRET: 'shared-server-confirmation-secret-32-chars!!',
        HEALTH_HOST: '0.0.0.0',
        HEALTH_PORT: '8081',
        METRICS_ENABLED: 'true',
        METRICS_AUTH_TOKEN: '',
      }),
    ).toThrowError(/METRICS_AUTH_TOKEN/i);

    // Succeeds with METRICS_AUTH_TOKEN configured
    const okWithAuth = loadConfig({
      DEPLOYMENT_MODE: 'shared-server',
      GITHUB_MODE: 'demo',
      ALLOWED_REPOS: 'acme/app',
      CONFIRMATION_SECRET: 'shared-server-confirmation-secret-32-chars!!',
      HEALTH_HOST: '0.0.0.0',
      HEALTH_PORT: '8081',
      METRICS_ENABLED: 'true',
      METRICS_AUTH_TOKEN: 'metrics-bearer-secret-token-2026',
    });
    expect(okWithAuth.metricsAuthToken).toBe('metrics-bearer-secret-token-2026');

    // Succeeds with METRICS_ENABLED=false
    const okDisabled = loadConfig({
      DEPLOYMENT_MODE: 'shared-server',
      GITHUB_MODE: 'demo',
      ALLOWED_REPOS: 'acme/app',
      CONFIRMATION_SECRET: 'shared-server-confirmation-secret-32-chars!!',
      HEALTH_HOST: '0.0.0.0',
      HEALTH_PORT: '8081',
      METRICS_ENABLED: 'false',
    });
    expect(okDisabled.metricsEnabled).toBe(false);
  });
});
