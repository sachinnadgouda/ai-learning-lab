import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { AiSummarizerPort, GitHubPort } from './application/ports.js';
import { ConfirmationService } from './domain/confirmationService.js';
import { PermissionPolicy } from './domain/permissionPolicy.js';
import { HttpAiSummarizer, NoopAiSummarizer } from './infrastructure/ai/HttpAiSummarizer.js';
import { SqliteAuditStore } from './infrastructure/audit/SqliteAuditStore.js';
import { loadConfig } from './infrastructure/config/env.js';
import { DemoGitHubProvider } from './infrastructure/github/DemoGitHubProvider.js';
import { GitHubRestClient } from './infrastructure/github/GitHubRestClient.js';
import {
  createEngineeringMcpServer,
  SERVER_NAME,
  SERVER_VERSION,
} from './infrastructure/mcp/createMcpServer.js';
import { HealthHttpServer, HealthStatus } from './infrastructure/observability/healthServer.js';
import { JsonStderrLogger } from './infrastructure/observability/logger.js';
import { InMemoryMetrics } from './infrastructure/observability/metrics.js';

export async function main(): Promise<void> {
  const startedAtMs = Date.now();
  const config = loadConfig();
  const logger = new JsonStderrLogger(config.logLevel);
  const metrics = new InMemoryMetrics();
  const auditStore = new SqliteAuditStore(config.auditDbPath);

  const github: GitHubPort =
    config.githubMode === 'live'
      ? new GitHubRestClient({
          baseUrl: config.githubApiBaseUrl,
          token: config.githubToken,
          timeoutMs: config.githubTimeoutMs,
          maxRetries: config.githubMaxRetries,
          maxPages: config.githubMaxPages,
          logger,
          metrics,
        })
      : new DemoGitHubProvider();

  const aiSummarizer: AiSummarizerPort =
    config.aiProvider === 'none'
      ? new NoopAiSummarizer()
      : new HttpAiSummarizer({
          provider: config.aiProvider,
          baseUrl: config.aiBaseUrl,
          model: config.aiModel,
          apiKey: config.aiApiKey,
          timeoutMs: config.aiTimeoutMs,
          logger,
          metrics,
        });

  const permissionPolicy = new PermissionPolicy({
    mode: config.permissionMode,
    allowedRepos: config.allowedRepos,
    actor: config.actorId,
    allowUnrestrictedDemoRepos: config.allowUnrestrictedDemoRepos,
  });

  const confirmationService = new ConfirmationService({
    secret: config.confirmationSecret,
    ttlSeconds: config.confirmationTtlSeconds,
    store: auditStore,
    deploymentMode: config.deploymentMode,
  });

  const getHealth = (): HealthStatus => {
    const ghHealth = github.healthCheck();
    let auditHealthy = true;
    let totalRecords = 0;
    try {
      totalRecords = auditStore.count();
    } catch {
      auditHealthy = false;
    }

    return {
      status: ghHealth.healthy && auditHealthy ? 'ok' : 'degraded',
      service: SERVER_NAME,
      version: SERVER_VERSION,
      timestamp: new Date().toISOString(),
      uptimeSeconds: Math.floor((Date.now() - startedAtMs) / 1000),
      github: {
        healthy: ghHealth.healthy,
        mode: github.mode,
        detail: ghHealth.detail,
        circuitState: ghHealth.circuitState,
      },
      auditStore: {
        healthy: auditHealthy,
        totalRecords,
      },
      permissionMode: permissionPolicy.getMode(),
      aiProvider: aiSummarizer.provider,
    };
  };

  const { server } = createEngineeringMcpServer({
    github,
    auditStore,
    reconciliationStore: auditStore,
    permissionPolicy,
    confirmationService,
    aiSummarizer,
    logger,
    metrics,
    getHealth,
  });

  const healthServer = new HealthHttpServer(
    config.healthHost,
    config.healthPort,
    getHealth,
    metrics,
    logger,
    {
      metricsEnabled: config.metricsEnabled,
      metricsAuthToken: config.metricsAuthToken,
    },
  );
  await healthServer.start();

  const transport = new StdioServerTransport();
  await server.connect(transport);

  logger.info('mcp.server.started', {
    version: SERVER_VERSION,
    deploymentMode: config.deploymentMode,
    githubMode: config.githubMode,
    permissionMode: config.permissionMode,
    allowedReposCount: config.allowedRepos.length,
    allowUnrestrictedDemoRepos: config.allowUnrestrictedDemoRepos,
    aiProvider: config.aiProvider,
    auditDbPath: config.auditDbPath,
    ephemeralConfirmationSecret: config.confirmationSecretEphemeral,
    metricsEnabled: config.metricsEnabled,
    metricsAuthProtected: config.metricsAuthToken.length > 0,
  });

  let shuttingDown = false;
  const shutdown = async (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    logger.info('mcp.server.shutting_down', { signal });
    try {
      await server.close();
      await healthServer.stop();
      auditStore.close();
    } catch (err) {
      logger.error('mcp.server.shutdown_error', {
        error: err instanceof Error ? err.message : String(err),
      });
    }
    process.exit(0);
  };

  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
}

const isDirectExecution =
  process.argv[1] !== undefined &&
  (process.argv[1].endsWith('/index.js') || process.argv[1].endsWith('/index.ts'));

if (isDirectExecution) {
  main().catch((err) => {
    process.stderr.write(
      `${JSON.stringify({
        timestamp: new Date().toISOString(),
        level: 'error',
        service: SERVER_NAME,
        message: 'Fatal startup failure',
        error: err instanceof Error ? err.message : String(err),
      })}\n`,
    );
    process.exit(1);
  });
}
