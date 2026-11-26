import {
  GitHubPort,
  GitHubRequestContext,
  LoggerPort,
  MetricsPort,
} from '../../application/ports.js';
import {
  CheckRun,
  CommitSummary,
  Issue,
  IssueComment,
  PullRequest,
  PullRequestFile,
  PullRequestReview,
  ReleaseSummary,
  Repository,
  WorkflowJob,
  WorkflowRun,
} from '../../domain/entities.js';
import { AppError, isAppError } from '../../domain/errors.js';
import { redactSecretsInString } from '../../domain/secretRedactor.js';
import { CircuitBreaker } from './circuitBreaker.js';

export interface GitHubRestClientOptions {
  baseUrl: string;
  token: string;
  timeoutMs: number;
  maxRetries: number;
  maxPages: number;
  baseBackoffMs?: number;
  circuitFailureThreshold?: number;
  circuitResetTimeoutMs?: number;
  logger: LoggerPort;
  metrics: MetricsPort;
  sleep?: (ms: number) => Promise<void>;
}

interface RawResponse<T> {
  data: T;
  headers: Headers;
}

export class GitHubRestClient implements GitHubPort {
  public readonly mode = 'live' as const;
  private readonly baseUrl: string;
  private readonly token: string;
  private readonly timeoutMs: number;
  private readonly maxRetries: number;
  private readonly maxPages: number;
  private readonly baseBackoffMs: number;
  private readonly circuitBreaker: CircuitBreaker;
  private readonly logger: LoggerPort;
  private readonly metrics: MetricsPort;
  private readonly sleepFn: (ms: number) => Promise<void>;

  constructor(options: GitHubRestClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, '');
    this.token = options.token;
    this.timeoutMs = options.timeoutMs;
    this.maxRetries = options.maxRetries;
    this.maxPages = options.maxPages;
    this.baseBackoffMs = options.baseBackoffMs ?? 250;
    this.circuitBreaker = new CircuitBreaker({
      failureThreshold: options.circuitFailureThreshold ?? 5,
      resetTimeoutMs: options.circuitResetTimeoutMs ?? 30_000,
    });
    this.logger = options.logger;
    this.metrics = options.metrics;
    this.sleepFn =
      options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  }

  public healthCheck(): { healthy: boolean; detail: string; circuitState?: string } {
    const state = this.circuitBreaker.getState();
    return {
      healthy: state !== 'OPEN',
      detail: `GitHub REST client (${this.baseUrl})`,
      circuitState: state,
    };
  }

  public async getRepository(
    owner: string,
    repo: string,
    ctx?: GitHubRequestContext,
  ): Promise<Repository> {
    const raw = await this.requestJson<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}`,
      { signal: ctx?.signal },
    );
    const data = raw.data;
    const ownerObj = (data.owner as Record<string, unknown> | undefined) ?? {};
    return {
      owner: String(ownerObj.login ?? owner),
      name: String(data.name ?? repo),
      fullName: String(data.full_name ?? `${owner}/${repo}`),
      description: data.description ? String(data.description) : null,
      defaultBranch: String(data.default_branch ?? 'main'),
      visibility: (data.visibility as Repository['visibility']) ?? (data.private ? 'private' : 'public'),
      openIssuesCount: Number(data.open_issues_count ?? 0),
      stargazersCount: Number(data.stargazers_count ?? 0),
      pushedAt: String(data.pushed_at ?? ''),
      url: String(data.html_url ?? ''),
    };
  }

  public async getPullRequest(
    owner: string,
    repo: string,
    pullNumber: number,
    ctx?: GitHubRequestContext,
  ): Promise<PullRequest> {
    const raw = await this.requestJson<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/pulls/${pullNumber}`,
      { signal: ctx?.signal },
    );
    return mapPullRequest(raw.data);
  }

  public async listPullRequests(
    owner: string,
    repo: string,
    options?: { state?: 'open' | 'closed' | 'all'; limit?: number; signal?: AbortSignal },
  ): Promise<PullRequest[]> {
    const state = options?.state ?? 'open';
    const limit = options?.limit ?? 30;
    const perPage = Math.min(100, limit);
    const items = await this.requestPaginated<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/pulls?state=${state}&sort=updated&direction=desc&per_page=${perPage}`,
      limit,
      options?.signal,
    );
    return items.map(mapPullRequest);
  }

  public async listPullRequestFiles(
    owner: string,
    repo: string,
    pullNumber: number,
    ctx?: GitHubRequestContext,
  ): Promise<PullRequestFile[]> {
    const items = await this.requestPaginated<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/pulls/${pullNumber}/files?per_page=100`,
      300,
      ctx?.signal,
    );
    return items.map((item) => ({
      filename: String(item.filename ?? ''),
      status: (item.status as PullRequestFile['status']) ?? 'modified',
      additions: Number(item.additions ?? 0),
      deletions: Number(item.deletions ?? 0),
      changes: Number(item.changes ?? 0),
    }));
  }

  public async listPullRequestReviews(
    owner: string,
    repo: string,
    pullNumber: number,
    ctx?: GitHubRequestContext,
  ): Promise<PullRequestReview[]> {
    const items = await this.requestPaginated<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/pulls/${pullNumber}/reviews?per_page=100`,
      100,
      ctx?.signal,
    );
    return items.map((item) => {
      const user = (item.user as Record<string, unknown> | undefined) ?? {};
      return {
        id: Number(item.id ?? 0),
        author: String(user.login ?? 'unknown'),
        state: (item.state as PullRequestReview['state']) ?? 'COMMENTED',
        submittedAt: String(item.submitted_at ?? ''),
        body: String(item.body ?? ''),
      };
    });
  }

  public async listCheckRunsForRef(
    owner: string,
    repo: string,
    ref: string,
    ctx?: GitHubRequestContext,
  ): Promise<CheckRun[]> {
    const raw = await this.requestJson<{ check_runs?: Array<Record<string, unknown>> }>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/commits/${encodeSegment(ref)}/check-runs?per_page=100`,
      { signal: ctx?.signal },
    );
    return (raw.data.check_runs ?? []).map((item) => ({
      id: Number(item.id ?? 0),
      name: String(item.name ?? 'unnamed-check'),
      status: (item.status as CheckRun['status']) ?? 'completed',
      conclusion: (item.conclusion as CheckRun['conclusion']) ?? null,
      startedAt: item.started_at ? String(item.started_at) : null,
      completedAt: item.completed_at ? String(item.completed_at) : null,
      detailsUrl: item.details_url ? String(item.details_url) : null,
    }));
  }

  public async getCodeOwners(
    owner: string,
    repo: string,
    ref?: string,
    ctx?: GitHubRequestContext,
  ): Promise<string | null> {
    const candidatePaths = ['.github/CODEOWNERS', 'CODEOWNERS', 'docs/CODEOWNERS'];
    const refQuery = ref ? `?ref=${encodeURIComponent(ref)}` : '';

    for (const candidate of candidatePaths) {
      try {
        const raw = await this.requestJson<Record<string, unknown>>(
          `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/contents/${candidate}${refQuery}`,
          { signal: ctx?.signal },
        );
        if (typeof raw.data.content === 'string') {
          const encoding = String(raw.data.encoding ?? 'base64');
          if (encoding === 'base64') {
            return Buffer.from(raw.data.content.replace(/\n/g, ''), 'base64').toString('utf-8');
          }
          return raw.data.content;
        }
      } catch (err) {
        if (isAppError(err) && err.code === 'NOT_FOUND') {
          continue;
        }
        throw err;
      }
    }
    return null;
  }

  public async listWorkflowRuns(
    owner: string,
    repo: string,
    options?: { branch?: string; limit?: number; signal?: AbortSignal },
  ): Promise<WorkflowRun[]> {
    const limit = options?.limit ?? 20;
    const perPage = Math.min(100, limit);
    const branchParam = options?.branch ? `&branch=${encodeURIComponent(options.branch)}` : '';
    const raw = await this.requestJson<{ workflow_runs?: Array<Record<string, unknown>> }>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/actions/runs?per_page=${perPage}${branchParam}`,
      { signal: options?.signal },
    );
    return (raw.data.workflow_runs ?? []).slice(0, limit).map((item) => ({
      id: Number(item.id ?? 0),
      name: String(item.name ?? 'workflow'),
      workflowPath: String(item.path ?? ''),
      branch: String(item.head_branch ?? ''),
      headSha: String(item.head_sha ?? ''),
      event: String(item.event ?? ''),
      status: String(item.status ?? ''),
      conclusion: item.conclusion ? String(item.conclusion) : null,
      createdAt: String(item.created_at ?? ''),
      updatedAt: String(item.updated_at ?? ''),
      url: String(item.html_url ?? ''),
    }));
  }

  public async listWorkflowJobs(
    owner: string,
    repo: string,
    runId: number,
    ctx?: GitHubRequestContext,
  ): Promise<WorkflowJob[]> {
    const raw = await this.requestJson<{ jobs?: Array<Record<string, unknown>> }>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/actions/runs/${runId}/jobs?per_page=100`,
      { signal: ctx?.signal },
    );
    return (raw.data.jobs ?? []).map((item) => ({
      id: Number(item.id ?? 0),
      name: String(item.name ?? 'job'),
      status: String(item.status ?? ''),
      conclusion: item.conclusion ? String(item.conclusion) : null,
      startedAt: item.started_at ? String(item.started_at) : null,
      completedAt: item.completed_at ? String(item.completed_at) : null,
      steps: Array.isArray(item.steps)
        ? (item.steps as Array<Record<string, unknown>>).map((step) => ({
            name: String(step.name ?? ''),
            status: String(step.status ?? ''),
            conclusion: step.conclusion ? String(step.conclusion) : null,
            number: Number(step.number ?? 0),
          }))
        : [],
    }));
  }

  public async listCommits(
    owner: string,
    repo: string,
    options?: { sinceIso?: string; limit?: number; signal?: AbortSignal },
  ): Promise<CommitSummary[]> {
    const limit = options?.limit ?? 30;
    const perPage = Math.min(100, limit);
    const sinceParam = options?.sinceIso ? `&since=${encodeURIComponent(options.sinceIso)}` : '';
    const items = await this.requestPaginated<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/commits?per_page=${perPage}${sinceParam}`,
      limit,
      options?.signal,
    );
    return items.map((item) => {
      const commitObj = (item.commit as Record<string, unknown> | undefined) ?? {};
      const commitAuthor = (commitObj.author as Record<string, unknown> | undefined) ?? {};
      const ghAuthor = (item.author as Record<string, unknown> | undefined) ?? {};
      return {
        sha: String(item.sha ?? ''),
        message: String(commitObj.message ?? ''),
        author: String(ghAuthor.login ?? commitAuthor.name ?? 'unknown'),
        authoredAt: String(commitAuthor.date ?? ''),
        url: String(item.html_url ?? ''),
      };
    });
  }

  public async listIssues(
    owner: string,
    repo: string,
    options?: {
      state?: 'open' | 'closed' | 'all';
      labels?: string[];
      sinceIso?: string;
      limit?: number;
      signal?: AbortSignal;
    },
  ): Promise<Issue[]> {
    const state = options?.state ?? 'open';
    const limit = options?.limit ?? 50;
    const perPage = Math.min(100, limit);
    const labelsParam =
      options?.labels && options.labels.length > 0
        ? `&labels=${encodeURIComponent(options.labels.join(','))}`
        : '';
    const sinceParam = options?.sinceIso ? `&since=${encodeURIComponent(options.sinceIso)}` : '';
    const items = await this.requestPaginated<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/issues?state=${state}&per_page=${perPage}${labelsParam}${sinceParam}`,
      limit,
      options?.signal,
    );
    return items.map(mapIssue);
  }

  public async listIssueComments(
    owner: string,
    repo: string,
    issueNumber: number,
    options?: { limit?: number; signal?: AbortSignal },
  ): Promise<IssueComment[]> {
    const limit = options?.limit ?? 50;
    const perPage = Math.min(100, limit);
    const items = await this.requestPaginated<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/issues/${issueNumber}/comments?per_page=${perPage}`,
      limit,
      options?.signal,
    );
    return items.map((item) => {
      const user = (item.user as Record<string, unknown> | undefined) ?? {};
      return {
        id: Number(item.id ?? 0),
        body: String(item.body ?? ''),
        author: String(user.login ?? 'unknown'),
        createdAt: String(item.created_at ?? ''),
        url: String(item.html_url ?? ''),
      };
    });
  }

  public async listReleases(
    owner: string,
    repo: string,
    limit = 15,
    ctx?: GitHubRequestContext,
  ): Promise<ReleaseSummary[]> {
    const perPage = Math.min(100, limit);
    const items = await this.requestPaginated<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/releases?per_page=${perPage}`,
      limit,
      ctx?.signal,
    );
    return items.map((item) => {
      const author = (item.author as Record<string, unknown> | undefined) ?? {};
      return {
        id: Number(item.id ?? 0),
        tagName: String(item.tag_name ?? ''),
        name: String(item.name ?? item.tag_name ?? ''),
        body: String(item.body ?? ''),
        draft: Boolean(item.draft),
        prerelease: Boolean(item.prerelease),
        author: String(author.login ?? 'unknown'),
        publishedAt: String(item.published_at ?? item.created_at ?? ''),
        url: String(item.html_url ?? ''),
      };
    });
  }

  public async createIssueComment(
    owner: string,
    repo: string,
    issueNumber: number,
    body: string,
    ctx?: GitHubRequestContext,
  ): Promise<IssueComment> {
    const raw = await this.requestJson<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/issues/${issueNumber}/comments`,
      { method: 'POST', body: { body }, signal: ctx?.signal },
    );
    const user = (raw.data.user as Record<string, unknown> | undefined) ?? {};
    return {
      id: Number(raw.data.id ?? 0),
      body: String(raw.data.body ?? body),
      author: String(user.login ?? 'authenticated-user'),
      createdAt: String(raw.data.created_at ?? new Date().toISOString()),
      url: String(raw.data.html_url ?? ''),
    };
  }

  public async createIssue(
    owner: string,
    repo: string,
    input: { title: string; body: string; labels?: string[] },
    ctx?: GitHubRequestContext,
  ): Promise<Issue> {
    const raw = await this.requestJson<Record<string, unknown>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/issues`,
      {
        method: 'POST',
        body: {
          title: input.title,
          body: input.body,
          labels: input.labels ?? [],
        },
        signal: ctx?.signal,
      },
    );
    return mapIssue(raw.data);
  }

  public async addLabelsToPullRequest(
    owner: string,
    repo: string,
    pullNumber: number,
    labels: string[],
    ctx?: GitHubRequestContext,
  ): Promise<string[]> {
    const raw = await this.requestJson<Array<Record<string, unknown>>>(
      `/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/issues/${pullNumber}/labels`,
      { method: 'POST', body: { labels }, signal: ctx?.signal },
    );
    return raw.data.map((item) => String(item.name ?? ''));
  }

  private async requestPaginated<T>(
    initialPath: string,
    limit: number,
    signal?: AbortSignal,
  ): Promise<T[]> {
    const results: T[] = [];
    let nextUrl: string | null = `${this.baseUrl}${initialPath}`;
    let page = 0;

    while (nextUrl && page < this.maxPages && results.length < limit) {
      page += 1;
      const response = await this.requestUrl<T[]>(nextUrl, { method: 'GET', signal });
      if (Array.isArray(response.data)) {
        results.push(...response.data);
      }
      if (results.length >= limit) {
        break;
      }
      nextUrl = parseNextLink(response.headers.get('link'));
    }

    return results.slice(0, limit);
  }

  private async requestJson<T>(
    path: string,
    options?: { method?: 'GET' | 'POST'; body?: Record<string, unknown>; signal?: AbortSignal },
  ): Promise<RawResponse<T>> {
    return this.requestUrl<T>(`${this.baseUrl}${path}`, options);
  }

  private async requestUrl<T>(
    url: string,
    options?: { method?: 'GET' | 'POST'; body?: Record<string, unknown>; signal?: AbortSignal },
  ): Promise<RawResponse<T>> {
    const method = options?.method ?? 'GET';
    const isWrite = method !== 'GET';
    const callerSignal = options?.signal;

    // Pre-send cancellation check: guaranteed no request left the process.
    if (callerSignal?.aborted) {
      throw new AppError(
        'CANCELLED',
        `GitHub ${method} request was cancelled before transmission; no upstream mutation occurred.`,
        {
          retryable: false,
          details: {
            method,
            phase: 'before_send',
            sideEffectPossible: false,
          },
        },
      );
    }

    this.circuitBreaker.beforeRequest('github');
    // Writes MUST NEVER be blindly retried because the upstream mutation may already have succeeded.
    const maxAttempts = isWrite ? 1 : this.maxRetries + 1;

    let lastError: AppError | null = null;

    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      if (callerSignal?.aborted) {
        throw new AppError(
          'CANCELLED',
          `GitHub ${method} request was cancelled before attempt ${attempt}; no upstream mutation occurred.`,
          {
            retryable: false,
            details: {
              method,
              phase: 'before_send',
              sideEffectPossible: false,
            },
          },
        );
      }

      const startMs = Date.now();
      const timeoutController = new AbortController();
      const timeout = setTimeout(() => timeoutController.abort(), this.timeoutMs);
      const effectiveSignal = callerSignal
        ? AbortSignal.any([callerSignal, timeoutController.signal])
        : timeoutController.signal;

      let requestDispatched = false;

      try {
        const headers: Record<string, string> = {
          Accept: 'application/vnd.github+json',
          'X-GitHub-Api-Version': '2022-11-28',
          'User-Agent': 'engineering-intelligence-mcp/1.0.0',
        };
        if (this.token) {
          headers.Authorization = `Bearer ${this.token}`;
        }
        if (options?.body) {
          headers['Content-Type'] = 'application/json';
        }

        requestDispatched = true;
        const response = await fetch(url, {
          method,
          headers,
          body: options?.body ? JSON.stringify(options.body) : undefined,
          signal: effectiveSignal,
        });

        const durationMs = Date.now() - startMs;
        this.metrics.recordUpstreamRequest({
          target: 'github',
          status: String(response.status),
          durationMs,
        });

        const rateRemainingRaw = response.headers.get('x-ratelimit-remaining');
        if (rateRemainingRaw !== null) {
          const parsedRemaining = Number(rateRemainingRaw);
          if (!Number.isNaN(parsedRemaining)) {
            this.metrics.recordRateLimit(parsedRemaining);
          }
        }

        if (response.ok) {
          this.circuitBreaker.recordSuccess();
          const data = (await response.json()) as T;
          return { data, headers: response.headers };
        }

        const bodyText = redactSecretsInString((await response.text()).slice(0, 300));
        const mappedError = this.mapHttpError(response.status, response.headers, bodyText);

        // Non-retryable client errors should NOT open the circuit breaker or be retried
        if (
          response.status === 401 ||
          response.status === 404 ||
          response.status === 422 ||
          (response.status === 403 && mappedError.code === 'PERMISSION_DENIED')
        ) {
          throw mappedError;
        }

        const shouldRecordBreakerFailure = response.status >= 500;
        if (shouldRecordBreakerFailure) {
          this.circuitBreaker.recordFailure();
        }

        if (isWrite && response.status >= 500) {
          throw new AppError(
            'AMBIGUOUS_WRITE_OUTCOME',
            `GitHub API returned HTTP ${response.status} for write request; upstream mutation state is ambiguous. Do not blindly retry—reconcile first.`,
            {
              statusCode: 504,
              retryable: false,
              details: {
                method,
                upstreamStatus: response.status,
                phase: 'in_flight_or_response_lost',
                sideEffectPossible: true,
                doNotBlindlyRetry: true,
              },
            },
          );
        }

        lastError = mappedError;

        if (!isWrite && attempt < maxAttempts && mappedError.retryable) {
          const delayMs = computeRetryDelayMs({
            attempt,
            baseBackoffMs: this.baseBackoffMs,
            retryAfterHeader: response.headers.get('retry-after'),
            rateResetHeader: response.headers.get('x-ratelimit-reset'),
          });
          this.logger.warn('github.request.retrying', {
            method,
            status: response.status,
            attempt,
            maxAttempts,
            delayMs,
          });
          await this.sleepWithAbort(delayMs, callerSignal);
          continue;
        }

        throw mappedError;
      } catch (err) {
        if (isAppError(err)) {
          throw err;
        }

        const durationMs = Date.now() - startMs;
        const isAbort =
          effectiveSignal.aborted || (err instanceof Error && err.name === 'AbortError');
        const cancelledByCaller = callerSignal?.aborted === true;

        this.metrics.recordUpstreamRequest({
          target: 'github',
          status: isAbort ? 'timeout' : 'network_error',
          durationMs,
        });
        if (!cancelledByCaller) {
          this.circuitBreaker.recordFailure();
        }

        if (isWrite && requestDispatched) {
          const reason = cancelledByCaller
            ? 'caller timeout/cancellation while request was in flight'
            : isAbort
              ? `GitHub HTTP timeout (${this.timeoutMs}ms) while write request was in flight`
              : `network/response failure after dispatching write request (${redactSecretsInString(err instanceof Error ? err.message : 'unknown error')})`;
          throw new AppError(
            'AMBIGUOUS_WRITE_OUTCOME',
            `Write outcome is ambiguous due to ${reason}. The mutation may have already succeeded on GitHub; do NOT blindly retry—run read_reconcile_write_outcome first.`,
            {
              retryable: false,
              cause: err,
              details: {
                method,
                phase: 'in_flight_or_response_lost',
                sideEffectPossible: true,
                doNotBlindlyRetry: true,
              },
            },
          );
        }

        if (cancelledByCaller) {
          throw new AppError(
            'CANCELLED',
            `GitHub ${method} request was cancelled by caller/timeout.`,
            {
              retryable: false,
              cause: err,
              details: { method, phase: 'in_flight', sideEffectPossible: false },
            },
          );
        }

        const safeMsg = isAbort
          ? `GitHub API request timed out after ${this.timeoutMs}ms.`
          : `GitHub API network error: ${redactSecretsInString(err instanceof Error ? err.message : 'unknown error')}`;

        lastError = new AppError('UPSTREAM_UNAVAILABLE', safeMsg, {
          retryable: true,
          cause: err,
        });

        if (!isWrite && attempt < maxAttempts) {
          const delayMs = computeRetryDelayMs({
            attempt,
            baseBackoffMs: this.baseBackoffMs,
            retryAfterHeader: null,
            rateResetHeader: null,
          });
          this.logger.warn('github.network.retrying', {
            method,
            attempt,
            maxAttempts,
            delayMs,
            reason: isAbort ? 'timeout' : 'network_error',
          });
          await this.sleepWithAbort(delayMs, callerSignal);
          continue;
        }

        throw lastError;
      } finally {
        clearTimeout(timeout);
      }
    }

    throw (
      lastError ??
      new AppError('UPSTREAM_UNAVAILABLE', 'GitHub API request failed after retries.', {
        retryable: !isWrite,
      })
    );
  }

  private async sleepWithAbort(ms: number, signal?: AbortSignal): Promise<void> {
    if (!signal) {
      await this.sleepFn(ms);
      return;
    }
    if (signal.aborted) {
      throw new AppError('CANCELLED', 'GitHub retry backoff cancelled before next attempt.', {
        retryable: false,
        details: { phase: 'before_send', sideEffectPossible: false },
      });
    }
    await new Promise<void>((resolve, reject) => {
      const onAbort = () => {
        reject(
          new AppError('CANCELLED', 'GitHub retry backoff cancelled before next attempt.', {
            retryable: false,
            details: { phase: 'before_send', sideEffectPossible: false },
          }),
        );
      };
      signal.addEventListener('abort', onAbort, { once: true });
      this.sleepFn(ms)
        .then(() => {
          signal.removeEventListener('abort', onAbort);
          resolve();
        })
        .catch((e) => {
          signal.removeEventListener('abort', onAbort);
          reject(e);
        });
    });
  }

  private mapHttpError(status: number, headers: Headers, safeBodyExcerpt: string): AppError {
    if (status === 401) {
      return new AppError(
        'AUTH_FAILED',
        'GitHub API authentication failed (HTTP 401). Verify GITHUB_TOKEN validity and expiration.',
        { statusCode: 401, retryable: false },
      );
    }

    const rateRemaining = headers.get('x-ratelimit-remaining');
    const retryAfter = headers.get('retry-after');
    const isRateLimited =
      status === 429 ||
      (status === 403 &&
        (rateRemaining === '0' ||
          retryAfter !== null ||
          /rate\s*limit/i.test(safeBodyExcerpt)));

    if (isRateLimited) {
      return new AppError(
        'RATE_LIMITED',
        'GitHub API rate limit exceeded. Wait before retrying or reduce request frequency.',
        {
          statusCode: 429,
          retryable: true,
          details: {
            retryAfter: retryAfter ?? undefined,
            rateLimitReset: headers.get('x-ratelimit-reset') ?? undefined,
          },
        },
      );
    }

    if (status === 403) {
      return new AppError(
        'PERMISSION_DENIED',
        'GitHub API rejected the request due to insufficient repository or token permissions (HTTP 403).',
        { statusCode: 403, retryable: false },
      );
    }

    if (status === 404) {
      return new AppError(
        'NOT_FOUND',
        'Requested GitHub resource was not found or is not accessible with current credentials (HTTP 404).',
        { statusCode: 404, retryable: false },
      );
    }

    if (status === 422) {
      return new AppError(
        'INVALID_ARGUMENT',
        `GitHub API rejected the request payload (HTTP 422): ${safeBodyExcerpt}`,
        { statusCode: 400, retryable: false },
      );
    }

    return new AppError(
      'UPSTREAM_UNAVAILABLE',
      `GitHub API returned unexpected HTTP ${status}.`,
      { statusCode: 502, retryable: status >= 500 },
    );
  }
}

function encodeSegment(segment: string): string {
  return encodeURIComponent(segment);
}

export function parseNextLink(linkHeader: string | null): string | null {
  if (!linkHeader) {
    return null;
  }
  const parts = linkHeader.split(',');
  for (const part of parts) {
    const match = part.match(/<([^>]+)>\s*;\s*rel="next"/i);
    if (match && match[1]) {
      return match[1];
    }
  }
  return null;
}

function computeRetryDelayMs(params: {
  attempt: number;
  baseBackoffMs: number;
  retryAfterHeader: string | null;
  rateResetHeader: string | null;
}): number {
  if (params.retryAfterHeader) {
    const seconds = Number(params.retryAfterHeader);
    if (!Number.isNaN(seconds) && seconds >= 0) {
      return Math.min(5000, Math.max(10, Math.round(seconds * 1000)));
    }
  }

  const exponential = params.baseBackoffMs * Math.pow(2, params.attempt - 1);
  const jitter = Math.floor(Math.random() * Math.max(10, Math.floor(exponential * 0.25)));
  return Math.min(5000, exponential + jitter);
}

function mapPullRequest(item: Record<string, unknown>): PullRequest {
  const user = (item.user as Record<string, unknown> | undefined) ?? {};
  const base = (item.base as Record<string, unknown> | undefined) ?? {};
  const head = (item.head as Record<string, unknown> | undefined) ?? {};
  const labels = Array.isArray(item.labels)
    ? (item.labels as Array<Record<string, unknown>>).map((l) => String(l.name ?? ''))
    : [];
  const requestedReviewers = Array.isArray(item.requested_reviewers)
    ? (item.requested_reviewers as Array<Record<string, unknown>>).map((r) => String(r.login ?? ''))
    : [];

  return {
    number: Number(item.number ?? 0),
    title: String(item.title ?? ''),
    body: String(item.body ?? ''),
    state: (item.state as 'open' | 'closed') ?? 'open',
    draft: Boolean(item.draft),
    merged: Boolean(item.merged || item.merged_at),
    author: String(user.login ?? 'unknown'),
    createdAt: String(item.created_at ?? ''),
    updatedAt: String(item.updated_at ?? ''),
    closedAt: item.closed_at ? String(item.closed_at) : null,
    mergedAt: item.merged_at ? String(item.merged_at) : null,
    baseBranch: String(base.ref ?? 'main'),
    headBranch: String(head.ref ?? ''),
    headSha: String(head.sha ?? ''),
    additions: Number(item.additions ?? 0),
    deletions: Number(item.deletions ?? 0),
    changedFiles: Number(item.changed_files ?? 0),
    labels,
    requestedReviewers,
    url: String(item.html_url ?? ''),
  };
}

function mapIssue(item: Record<string, unknown>): Issue {
  const user = (item.user as Record<string, unknown> | undefined) ?? {};
  const labels = Array.isArray(item.labels)
    ? (item.labels as Array<Record<string, unknown>>).map((l) =>
        typeof l === 'string' ? l : String(l.name ?? ''),
      )
    : [];
  const assignees = Array.isArray(item.assignees)
    ? (item.assignees as Array<Record<string, unknown>>).map((a) => String(a.login ?? ''))
    : [];

  return {
    number: Number(item.number ?? 0),
    title: String(item.title ?? ''),
    body: String(item.body ?? ''),
    state: (item.state as 'open' | 'closed') ?? 'open',
    author: String(user.login ?? 'unknown'),
    labels,
    assignees,
    commentsCount: Number(item.comments ?? 0),
    isPullRequest: Boolean(item.pull_request),
    createdAt: String(item.created_at ?? ''),
    updatedAt: String(item.updated_at ?? ''),
    closedAt: item.closed_at ? String(item.closed_at) : null,
    url: String(item.html_url ?? ''),
  };
}
