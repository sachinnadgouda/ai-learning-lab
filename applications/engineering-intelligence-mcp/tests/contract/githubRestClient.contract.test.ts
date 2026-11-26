import { createServer, IncomingMessage, Server, ServerResponse } from 'node:http';
import { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { AppError } from '../../src/domain/errors.js';
import { GitHubRestClient } from '../../src/infrastructure/github/GitHubRestClient.js';
import { JsonStderrLogger } from '../../src/infrastructure/observability/logger.js';
import { InMemoryMetrics } from '../../src/infrastructure/observability/metrics.js';

interface MockServerHandle {
  baseUrl: string;
  close: () => Promise<void>;
}

async function startMockServer(
  handler: (req: IncomingMessage, res: ServerResponse) => void,
): Promise<MockServerHandle> {
  const server: Server = createServer(handler);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
  const { port } = server.address() as AddressInfo;
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}

describe('GitHubRestClient HTTP Contract Tests', () => {
  const servers: MockServerHandle[] = [];

  afterEach(async () => {
    while (servers.length > 0) {
      await servers.pop()?.close();
    }
  });

  function createClient(
    baseUrl: string,
    overrides: Partial<ConstructorParameters<typeof GitHubRestClient>[0]> = {},
  ) {
    const logs: string[] = [];
    const metrics = new InMemoryMetrics();
    const client = new GitHubRestClient({
      baseUrl,
      token: 'ghp_superSecretContractTokenValue9876543210',
      timeoutMs: 1000,
      maxRetries: 2,
      maxPages: 3,
      baseBackoffMs: 5,
      circuitFailureThreshold: 3,
      circuitResetTimeoutMs: 5000,
      logger: new JsonStderrLogger('debug', (line) => logs.push(line)),
      metrics,
      sleep: async () => {},
      ...overrides,
    });
    return { client, logs, metrics };
  }

  it('follows Link header pagination and bounds total pages by maxPages', async () => {
    let requests = 0;
    const srv = await startMockServer((req, res) => {
      requests += 1;
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      const page = Number(url.searchParams.get('page') ?? '1');
      expect(req.headers.authorization).toBe('Bearer ghp_superSecretContractTokenValue9876543210');

      const nextLink =
        page < 4
          ? `<${srv.baseUrl}/repos/acme/app/commits?per_page=2&page=${page + 1}>; rel="next"`
          : '';
      res.writeHead(200, {
        'content-type': 'application/json',
        'x-ratelimit-remaining': '4990',
        ...(nextLink ? { link: nextLink } : {}),
      });
      res.end(
        JSON.stringify([
          {
            sha: `sha-${page}-a`,
            html_url: `https://github.com/acme/app/commit/sha-${page}-a`,
            commit: { message: `Commit ${page}A`, author: { name: 'dev', date: '2026-11-25T00:00:00Z' } },
          },
          {
            sha: `sha-${page}-b`,
            html_url: `https://github.com/acme/app/commit/sha-${page}-b`,
            commit: { message: `Commit ${page}B`, author: { name: 'dev', date: '2026-11-25T00:00:00Z' } },
          },
        ]),
      );
    });
    servers.push(srv);

    const { client, metrics } = createClient(srv.baseUrl, { maxPages: 2 });
    const commits = await client.listCommits('acme', 'app', { limit: 10 });

    // Bounded to 2 pages x 2 items = 4 commits even though server had 4 pages
    expect(requests).toBe(2);
    expect(commits).toHaveLength(4);
    expect(commits.map((c) => c.sha)).toEqual(['sha-1-a', 'sha-1-b', 'sha-2-a', 'sha-2-b']);
    expect(metrics.renderPrometheus()).toContain('github_rate_limit_remaining 4990');
  });

  it('maps HTTP 401 to AUTH_FAILED without retrying and never leaks GITHUB_TOKEN', async () => {
    let attempts = 0;
    const srv = await startMockServer((_req, res) => {
      attempts += 1;
      res.writeHead(401, { 'content-type': 'application/json' });
      // Intentionally echo token in mock error body to verify secret redaction
      res.end(
        JSON.stringify({
          message: 'Bad credentials for ghp_superSecretContractTokenValue9876543210',
        }),
      );
    });
    servers.push(srv);

    const { client, logs } = createClient(srv.baseUrl);
    try {
      await client.getRepository('acme', 'app');
      expect.unreachable('Expected AUTH_FAILED');
    } catch (err) {
      expect(err).toBeInstanceOf(AppError);
      const appErr = err as AppError;
      expect(appErr.code).toBe('AUTH_FAILED');
      expect(appErr.retryable).toBe(false);
      expect(appErr.message).not.toContain('ghp_superSecretContractTokenValue9876543210');
    }

    expect(attempts).toBe(1);
    expect(logs.join('\n')).not.toContain('ghp_superSecretContractTokenValue9876543210');
  });

  it('handles HTTP 429 and 403 rate limits with Retry-After and surfaces RATE_LIMITED when exhausted', async () => {
    let attempts = 0;
    const srv = await startMockServer((_req, res) => {
      attempts += 1;
      res.writeHead(attempts === 1 ? 429 : 403, {
        'content-type': 'application/json',
        'x-ratelimit-remaining': '0',
        'retry-after': '1',
      });
      res.end(JSON.stringify({ message: 'API rate limit exceeded' }));
    });
    servers.push(srv);

    const { client } = createClient(srv.baseUrl, { maxRetries: 1 });
    try {
      await client.getRepository('acme', 'app');
      expect.unreachable('Expected RATE_LIMITED');
    } catch (err) {
      expect(err).toBeInstanceOf(AppError);
      expect((err as AppError).code).toBe('RATE_LIMITED');
      expect((err as AppError).retryable).toBe(true);
    }
    expect(attempts).toBe(2);
  });

  it('retries transient HTTP 500 errors and recovers cleanly on subsequent 200', async () => {
    let attempts = 0;
    const srv = await startMockServer((_req, res) => {
      attempts += 1;
      if (attempts < 3) {
        res.writeHead(500, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ message: 'Internal Server Error' }));
        return;
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(
        JSON.stringify({
          name: 'app',
          full_name: 'acme/app',
          owner: { login: 'acme' },
          default_branch: 'main',
          visibility: 'private',
          open_issues_count: 3,
          stargazers_count: 10,
          pushed_at: '2026-11-26T00:00:00Z',
          html_url: 'https://github.com/acme/app',
        }),
      );
    });
    servers.push(srv);

    const { client } = createClient(srv.baseUrl, { maxRetries: 2 });
    const repo = await client.getRepository('acme', 'app');
    expect(attempts).toBe(3);
    expect(repo.fullName).toBe('acme/app');
  });

  it('aborts slow upstream requests on timeout and opens circuit breaker after repeated failures', async () => {
    let attempts = 0;
    const srv = await startMockServer((_req, res) => {
      attempts += 1;
      setTimeout(() => {
        if (!res.writableEnded) {
          res.writeHead(200, { 'content-type': 'application/json' });
          res.end('{}');
        }
      }, 400);
    });
    servers.push(srv);

    const { client } = createClient(srv.baseUrl, {
      timeoutMs: 60,
      maxRetries: 1,
      circuitFailureThreshold: 2,
    });

    await expect(client.getRepository('acme', 'app')).rejects.toMatchObject({
      code: 'UPSTREAM_UNAVAILABLE',
    });
    expect(attempts).toBe(2);

    // Circuit breaker is now OPEN -> third call fails immediately without hitting mock server
    await expect(client.getRepository('acme', 'app')).rejects.toMatchObject({
      code: 'CIRCUIT_OPEN',
    });
    expect(attempts).toBe(2);
    expect(client.healthCheck().circuitState).toBe('OPEN');
  });

  it('fetches and base64-decodes CODEOWNERS with fallback from .github/CODEOWNERS to CODEOWNERS', async () => {
    const srv = await startMockServer((req, res) => {
      if (req.url?.includes('.github/CODEOWNERS')) {
        res.writeHead(404, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ message: 'Not Found' }));
        return;
      }
      if (req.url?.includes('/contents/CODEOWNERS')) {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(
          JSON.stringify({
            encoding: 'base64',
            content: Buffer.from('* @acme/platform\nsrc/auth/ @acme/sec\n').toString('base64'),
          }),
        );
        return;
      }
      res.writeHead(404);
      res.end();
    });
    servers.push(srv);

    const { client } = createClient(srv.baseUrl);
    const content = await client.getCodeOwners('acme', 'app', 'main');
    expect(content).toContain('@acme/sec');
  });
});
