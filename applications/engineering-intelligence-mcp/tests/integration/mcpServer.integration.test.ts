import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { afterEach, describe, expect, it } from 'vitest';
import { GitHubPort } from '../../src/application/ports.js';
import { ConfirmationService } from '../../src/domain/confirmationService.js';
import { AppError } from '../../src/domain/errors.js';
import { PermissionMode } from '../../src/domain/entities.js';
import { PermissionPolicy } from '../../src/domain/permissionPolicy.js';
import { NoopAiSummarizer } from '../../src/infrastructure/ai/HttpAiSummarizer.js';
import { SqliteAuditStore } from '../../src/infrastructure/audit/SqliteAuditStore.js';
import { DemoGitHubProvider } from '../../src/infrastructure/github/DemoGitHubProvider.js';
import {
  createEngineeringMcpServer,
  SERVER_NAME,
  SERVER_VERSION,
} from '../../src/infrastructure/mcp/createMcpServer.js';
import { HealthHttpServer } from '../../src/infrastructure/observability/healthServer.js';
import { JsonStderrLogger } from '../../src/infrastructure/observability/logger.js';
import { InMemoryMetrics } from '../../src/infrastructure/observability/metrics.js';

interface TestHarness {
  client: Client;
  auditStore: SqliteAuditStore;
  metrics: InMemoryMetrics;
  close: () => Promise<void>;
}

interface ParsedToolPayload {
  ok: boolean;
  correlationId: string;
  toolName: string;
  data?: Record<string, unknown>;
  error?: {
    code: string;
    message: string;
    retryable: boolean;
    details?: Record<string, unknown>;
  };
}

function parseResult(res: Awaited<ReturnType<Client['callTool']>>): ParsedToolPayload {
  const content = Array.isArray(res.content) ? res.content : [];
  const textPart = content.find((c) => c.type === 'text') as { text: string } | undefined;
  expect(textPart).toBeDefined();
  return JSON.parse(textPart!.text) as ParsedToolPayload;
}

describe('MCP Protocol Integration Tests (InMemoryTransport)', () => {
  const harnesses: TestHarness[] = [];

  afterEach(async () => {
    while (harnesses.length > 0) {
      await harnesses.pop()?.close();
    }
  });

  async function createHarness(options?: {
    permissionMode?: PermissionMode;
    allowedRepos?: string[];
    github?: GitHubPort;
  }): Promise<TestHarness> {
    const auditStore = new SqliteAuditStore(':memory:');
    const logger = new JsonStderrLogger('error', () => {});
    const metrics = new InMemoryMetrics();
    const github = options?.github ?? new DemoGitHubProvider();
    const permissionPolicy = new PermissionPolicy({
      mode: options?.permissionMode ?? 'read-only',
      allowedRepos: options?.allowedRepos ?? ['acme-eng/payments-platform'],
      actor: 'integration-tester',
    });
    const confirmationService = new ConfirmationService({
      secret: 'integration-test-confirmation-secret-2026',
      ttlSeconds: 300,
      store: auditStore,
    });
    const aiSummarizer = new NoopAiSummarizer();

    const { server } = createEngineeringMcpServer({
      github,
      auditStore,
      permissionPolicy,
      confirmationService,
      aiSummarizer,
      logger,
      metrics,
      getHealth: () => ({
        status: github.healthCheck().healthy ? 'ok' : 'degraded',
        service: SERVER_NAME,
        version: SERVER_VERSION,
        timestamp: new Date().toISOString(),
        uptimeSeconds: 1,
        github: {
          healthy: github.healthCheck().healthy,
          mode: github.mode,
          detail: github.healthCheck().detail,
          circuitState: github.healthCheck().circuitState,
        },
        auditStore: { healthy: true, totalRecords: auditStore.count() },
        permissionMode: permissionPolicy.getMode(),
        aiProvider: aiSummarizer.provider,
      }),
    });

    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    await server.connect(serverTransport);

    const client = new Client({ name: 'vitest-mcp-client', version: '1.0.0' }, { capabilities: {} });
    await client.connect(clientTransport);

    const harness: TestHarness = {
      client,
      auditStore,
      metrics,
      close: async () => {
        await client.close();
        await server.close();
        auditStore.close();
      },
    };
    harnesses.push(harness);
    return harness;
  }

  it('discovers all 9 tools with explicit read/write annotations and reads MCP resources', async () => {
    const { client } = await createHarness();

    const tools = await client.listTools();
    expect(tools.tools).toHaveLength(9);
    const byName = new Map(tools.tools.map((t) => [t.name, t]));
    expect(byName.get('analyze_pull_request')?.annotations?.readOnlyHint).toBe(true);
    expect(byName.get('investigate_ci_failures')?.annotations?.readOnlyHint).toBe(true);
    expect(byName.get('read_engineering_activity')?.annotations?.readOnlyHint).toBe(true);
    expect(byName.get('read_repo_insights')?.annotations?.readOnlyHint).toBe(true);
    expect(byName.get('read_search_issues_and_prs')?.annotations?.readOnlyHint).toBe(true);
    expect(byName.get('read_reconcile_write_outcome')?.annotations?.readOnlyHint).toBe(true);
    expect(byName.get('write_create_issue_comment')?.annotations?.readOnlyHint).toBe(false);
    expect(byName.get('write_create_issue')?.annotations?.readOnlyHint).toBe(false);
    expect(byName.get('write_add_pull_request_labels')?.annotations?.readOnlyHint).toBe(false);

    const resources = await client.listResources();
    expect(resources.resources.map((r) => r.uri)).toEqual(
      expect.arrayContaining(['engineering://audit/recent', 'engineering://health']),
    );

    const overview = await client.readResource({
      uri: 'engineering://repo/acme-eng/payments-platform/overview',
    });
    const overviewPayload = JSON.parse(
      (overview.contents[0] as { text: string }).text,
    ) as Record<string, unknown>;
    expect((overviewPayload.repository as Record<string, unknown>).fullName).toBe(
      'acme-eng/payments-platform',
    );

    const health = await client.readResource({ uri: 'engineering://health' });
    const healthPayload = JSON.parse(
      (health.contents[0] as { text: string }).text,
    ) as Record<string, unknown>;
    expect((healthPayload.health as Record<string, unknown>).status).toBe('ok');
  });

  it('runs read-only engineering intelligence tools and catches prompt injection in PR #145 and issue #144', async () => {
    const { client, auditStore } = await createHarness();

    const pr142 = parseResult(
      await client.callTool({
        name: 'analyze_pull_request',
        arguments: { owner: 'acme-eng', repo: 'payments-platform', pullNumber: 142 },
      }),
    );
    expect(pr142.ok).toBe(true);
    expect(pr142.data?.riskLevel).toBe('CRITICAL');
    expect((pr142.data?.codeOwners as unknown[]).length).toBeGreaterThan(0);

    const pr145 = parseResult(
      await client.callTool({
        name: 'analyze_pull_request',
        arguments: { owner: 'acme-eng', repo: 'payments-platform', pullNumber: 145 },
      }),
    );
    expect(pr145.ok).toBe(true);
    expect((pr145.data?.injectionWarnings as unknown[]).length).toBeGreaterThan(0);
    expect(String(pr145.data?.fencedBody)).toContain('<untrusted_github_content');
    expect(String(pr145.data?.fencedBody)).toContain('[REDACTED_UNTRUSTED_DIRECTIVE]');

    const ci = parseResult(
      await client.callTool({
        name: 'investigate_ci_failures',
        arguments: { owner: 'acme-eng', repo: 'payments-platform', maxRuns: 10 },
      }),
    );
    expect(ci.ok).toBe(true);
    expect(ci.data?.failedRuns).toBe(3);
    expect((ci.data?.injectionWarnings as unknown[]).length).toBeGreaterThan(0);

    const search = parseResult(
      await client.callTool({
        name: 'read_search_issues_and_prs',
        arguments: { owner: 'acme-eng', repo: 'payments-platform', state: 'open', type: 'issue' },
      }),
    );
    expect(search.ok).toBe(true);
    expect((search.data?.injectionWarnings as unknown[]).length).toBeGreaterThan(0);

    const activity = parseResult(
      await client.callTool({
        name: 'read_engineering_activity',
        arguments: { owner: 'acme-eng', repo: 'payments-platform', days: 30 },
      }),
    );
    expect(activity.ok).toBe(true);

    // SQLite audit trail captured all 5 invocations including injection counts
    const records = auditStore.listRecent({ limit: 10 });
    expect(records).toHaveLength(5);
    expect(records.some((r) => r.toolName === 'analyze_pull_request' && r.injectionWarningsCount > 0)).toBe(
      true,
    );
  });

  it('rejects invalid arguments via Zod schema validation', async () => {
    const { client } = await createHarness();
    const badResult = await client.callTool({
      name: 'analyze_pull_request',
      arguments: { owner: '../etc/passwd', repo: 'payments-platform', pullNumber: -5 },
    });
    expect(badResult.isError).toBe(true);
  });

  it('blocks write tools in read-only mode and enforces repo allowlist', async () => {
    const { client, auditStore } = await createHarness({
      permissionMode: 'read-only',
      allowedRepos: ['acme-eng/payments-platform'],
    });

    const deniedWrite = parseResult(
      await client.callTool({
        name: 'write_create_issue',
        arguments: {
          owner: 'acme-eng',
          repo: 'payments-platform',
          title: 'Unauthorized issue',
          body: 'Should be blocked',
        },
      }),
    );
    expect(deniedWrite.ok).toBe(false);
    expect(deniedWrite.error?.code).toBe('PERMISSION_DENIED');

    const deniedRepo = parseResult(
      await client.callTool({
        name: 'read_repo_insights',
        arguments: { owner: 'forbidden-org', repo: 'private-repo' },
      }),
    );
    expect(deniedRepo.ok).toBe(false);
    expect(deniedRepo.error?.code).toBe('PERMISSION_DENIED');

    const records = auditStore.listRecent({ limit: 5 });
    expect(records.every((r) => r.outcome === 'DENIED')).toBe(true);
  });

  it('enforces full two-phase confirmation workflow for writes, prevents replay, and redacts confirmation tokens in audit logs', async () => {
    const { client, auditStore } = await createHarness({
      permissionMode: 'write-with-confirmation',
    });

    const createIssueArgs = {
      owner: 'acme-eng',
      repo: 'payments-platform',
      title: 'Track JWKS rotation flakiness',
      body: 'Follow-up issue generated after human review of PR #142.',
      labels: ['security', 'reliability'],
    };

    // Phase 1: no token -> returns CONFIRMATION_REQUIRED, does NOT mutate GitHub
    const challenge = parseResult(
      await client.callTool({
        name: 'write_create_issue',
        arguments: createIssueArgs,
      }),
    );
    expect(challenge.ok).toBe(false);
    expect(challenge.error?.code).toBe('CONFIRMATION_REQUIRED');
    const token = String(challenge.error?.details?.confirmationToken ?? '');
    expect(token).toMatch(/^conf_v2\./);

    // Tampered arguments with valid token -> rejected
    const tampered = parseResult(
      await client.callTool({
        name: 'write_create_issue',
        arguments: { ...createIssueArgs, title: 'Injected malicious title', confirmationToken: token },
      }),
    );
    expect(tampered.ok).toBe(false);
    expect(tampered.error?.code).toBe('PERMISSION_DENIED');

    // Phase 2: exact same args + token -> executes write
    const executed = parseResult(
      await client.callTool({
        name: 'write_create_issue',
        arguments: { ...createIssueArgs, confirmationToken: token },
      }),
    );
    expect(executed.ok).toBe(true);
    expect((executed.data?.issue as Record<string, unknown>).title).toBe(createIssueArgs.title);

    // Phase 3: replay same token -> rejected
    const replay = parseResult(
      await client.callTool({
        name: 'write_create_issue',
        arguments: { ...createIssueArgs, confirmationToken: token },
      }),
    );
    expect(replay.ok).toBe(false);
    expect(replay.error?.code).toBe('PERMISSION_DENIED');

    // Verify audit records exist and NEVER contain raw confirmation token
    const auditRecords = auditStore.listRecent({ limit: 10 });
    expect(auditRecords.length).toBe(4);
    for (const rec of auditRecords) {
      expect(rec.redactedArgsJson).not.toContain(token);
      expect(rec.summary).not.toContain(token);
    }

    // Verify audit resource exposes the persisted SQLite records
    const auditResource = await client.readResource({ uri: 'engineering://audit/recent' });
    const parsedAudit = JSON.parse(
      (auditResource.contents[0] as { text: string }).text,
    ) as { totalRecords: number };
    expect(parsedAudit.totalRecords).toBe(4);
  });

  it('handles unavailable GitHub upstream gracefully without leaking internals', async () => {
    const failingGithub = new DemoGitHubProvider();
    failingGithub.getPullRequest = async () => {
      throw new AppError('UPSTREAM_UNAVAILABLE', 'GitHub API is currently unreachable', {
        retryable: true,
      });
    };

    const { client } = await createHarness({ github: failingGithub });
    const res = parseResult(
      await client.callTool({
        name: 'analyze_pull_request',
        arguments: { owner: 'acme-eng', repo: 'payments-platform', pullNumber: 142 },
      }),
    );
    expect(res.ok).toBe(false);
    expect(res.error?.code).toBe('UPSTREAM_UNAVAILABLE');
    expect(res.error?.retryable).toBe(true);
  });

  it('serves /health, /ready, and Prometheus /metrics via HealthHttpServer', async () => {
    const metrics = new InMemoryMetrics();
    metrics.recordToolExecution({
      toolName: 'analyze_pull_request',
      permissionClass: 'READ',
      outcome: 'SUCCESS',
      durationMs: 42,
      injectionWarningsCount: 1,
    });
    const logger = new JsonStderrLogger('error', () => {});
    const healthServer = new HealthHttpServer(
      '127.0.0.1',
      19876,
      () => ({
        status: 'ok',
        service: SERVER_NAME,
        version: SERVER_VERSION,
        timestamp: new Date().toISOString(),
        uptimeSeconds: 5,
        github: { healthy: true, mode: 'demo', detail: 'ok', circuitState: 'CLOSED' },
        auditStore: { healthy: true, totalRecords: 1 },
        permissionMode: 'read-only',
        aiProvider: 'none',
      }),
      metrics,
      logger,
    );

    await healthServer.start();
    try {
      const healthRes = await fetch('http://127.0.0.1:19876/health');
      expect(healthRes.status).toBe(200);
      const body = (await healthRes.json()) as { status: string };
      expect(body.status).toBe('ok');

      const metricsRes = await fetch('http://127.0.0.1:19876/metrics');
      expect(metricsRes.status).toBe(200);
      const promText = await metricsRes.text();
      expect(promText).toContain('mcp_tool_executions_total');
      expect(promText).toContain('mcp_prompt_injection_warnings_total 1');
    } finally {
      await healthServer.stop();
    }
  });

  it('protects /metrics with METRICS_AUTH_TOKEN and supports disabling /metrics', async () => {
    const metrics = new InMemoryMetrics();
    const logger = new JsonStderrLogger('error', () => {});
    const healthPayload = () => ({
      status: 'ok' as const,
      service: SERVER_NAME,
      version: SERVER_VERSION,
      timestamp: new Date().toISOString(),
      uptimeSeconds: 5,
      github: { healthy: true, mode: 'demo' as const, detail: 'ok', circuitState: 'CLOSED' },
      auditStore: { healthy: true, totalRecords: 0 },
      permissionMode: 'read-only',
      aiProvider: 'none',
    });

    const authToken = 'metrics-secret-token-2026-strong';
    const protectedServer = new HealthHttpServer(
      '127.0.0.1',
      19877,
      healthPayload,
      metrics,
      logger,
      { metricsEnabled: true, metricsAuthToken: authToken },
    );

    await protectedServer.start();
    try {
      // /health stays accessible for liveness checks
      const healthRes = await fetch('http://127.0.0.1:19877/health');
      expect(healthRes.status).toBe(200);

      // /metrics rejects unauthenticated or wrong-token calls with 401
      const unauthMetrics = await fetch('http://127.0.0.1:19877/metrics');
      expect(unauthMetrics.status).toBe(401);

      const wrongTokenMetrics = await fetch('http://127.0.0.1:19877/metrics', {
        headers: { Authorization: 'Bearer wrong-token-value-1234' },
      });
      expect(wrongTokenMetrics.status).toBe(401);

      // Valid Bearer token succeeds
      const bearerOk = await fetch('http://127.0.0.1:19877/metrics', {
        headers: { Authorization: `Bearer ${authToken}` },
      });
      expect(bearerOk.status).toBe(200);

      // Valid X-Metrics-Token succeeds
      const headerOk = await fetch('http://127.0.0.1:19877/metrics', {
        headers: { 'X-Metrics-Token': authToken },
      });
      expect(headerOk.status).toBe(200);
    } finally {
      await protectedServer.stop();
    }

    // Disabled /metrics returns 404
    const disabledMetricsServer = new HealthHttpServer(
      '127.0.0.1',
      19878,
      healthPayload,
      metrics,
      logger,
      { metricsEnabled: false },
    );
    await disabledMetricsServer.start();
    try {
      const disabledRes = await fetch('http://127.0.0.1:19878/metrics');
      expect(disabledRes.status).toBe(404);
    } finally {
      await disabledMetricsServer.stop();
    }
  });
});
