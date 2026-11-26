import { McpServer, ResourceTemplate } from '@modelcontextprotocol/sdk/server/mcp.js';
import { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import {
  AiSummarizerPort,
  AuditStorePort,
  GitHubPort,
  LoggerPort,
  MetricsPort,
  WriteReconciliationStorePort,
} from '../../application/ports.js';
import {
  AddPullRequestLabelsInput,
  AddPullRequestLabelsInputSchema,
  AnalyzePullRequestInput,
  AnalyzePullRequestInputSchema,
  ComputeRepoInsightsInput,
  ComputeRepoInsightsInputSchema,
  CreateIssueCommentInput,
  CreateIssueCommentInputSchema,
  CreateIssueInput,
  CreateIssueInputSchema,
  InvestigateCiFailuresInput,
  InvestigateCiFailuresInputSchema,
  ReconcileWriteOutcomeInput,
  ReconcileWriteOutcomeInputSchema,
  SearchIssuesAndPrsInput,
  SearchIssuesAndPrsInputSchema,
  SummarizeEngineeringActivityInput,
  SummarizeEngineeringActivityInputSchema,
} from '../../application/schemas.js';
import {
  ToolExecutionOrchestrator,
  ToolExecutionResponse,
} from '../../application/toolExecutionOrchestrator.js';
import { AddPullRequestLabelsUseCase } from '../../application/usecases/addPullRequestLabels.js';
import { AnalyzePullRequestUseCase } from '../../application/usecases/analyzePullRequest.js';
import { ComputeRepoInsightsUseCase } from '../../application/usecases/computeRepoInsights.js';
import { CreateIssueUseCase } from '../../application/usecases/createIssue.js';
import { CreateIssueCommentUseCase } from '../../application/usecases/createIssueComment.js';
import { InvestigateCiFailuresUseCase } from '../../application/usecases/investigateCiFailures.js';
import { ReconcileWriteOutcomeUseCase } from '../../application/usecases/reconcileWriteOutcome.js';
import { SearchIssuesAndPrsUseCase } from '../../application/usecases/searchIssuesAndPrs.js';
import { SummarizeEngineeringActivityUseCase } from '../../application/usecases/summarizeEngineeringActivity.js';
import { CiFailureAnalyzer } from '../../domain/ciFailureAnalyzer.js';
import { CodeOwnersParser } from '../../domain/codeOwnersParser.js';
import { ConfirmationService } from '../../domain/confirmationService.js';
import { PermissionPolicy } from '../../domain/permissionPolicy.js';
import { PullRequestRiskAnalyzer } from '../../domain/prRiskAnalyzer.js';
import { PromptInjectionGuard } from '../../domain/promptInjectionGuard.js';
import { RepoInsightsCalculator } from '../../domain/repoInsightsCalculator.js';
import { HealthStatus } from '../observability/healthServer.js';

export const SERVER_NAME = 'engineering-intelligence-mcp';
export const SERVER_VERSION = '1.0.0';

export interface McpServerDependencies {
  github: GitHubPort;
  auditStore: AuditStorePort;
  reconciliationStore?: WriteReconciliationStorePort;
  permissionPolicy: PermissionPolicy;
  confirmationService: ConfirmationService;
  aiSummarizer: AiSummarizerPort;
  logger: LoggerPort;
  metrics: MetricsPort;
  getHealth: () => HealthStatus;
  toolTimeoutMs?: number;
}

export interface CreatedMcpServer {
  server: McpServer;
  orchestrator: ToolExecutionOrchestrator;
}

export function createEngineeringMcpServer(deps: McpServerDependencies): CreatedMcpServer {
  const server = new McpServer(
    { name: SERVER_NAME, version: SERVER_VERSION },
    {
      capabilities: {
        tools: {},
        resources: {},
      },
    },
  );

  const injectionGuard = new PromptInjectionGuard();
  const codeOwnersParser = new CodeOwnersParser();
  const prAnalyzer = new PullRequestRiskAnalyzer(codeOwnersParser, injectionGuard);
  const ciAnalyzer = new CiFailureAnalyzer(injectionGuard);
  const insightsCalculator = new RepoInsightsCalculator();

  const analyzePrUseCase = new AnalyzePullRequestUseCase(
    deps.github,
    prAnalyzer,
    deps.aiSummarizer,
  );
  const investigateCiUseCase = new InvestigateCiFailuresUseCase(
    deps.github,
    ciAnalyzer,
    deps.aiSummarizer,
  );
  const summarizeActivityUseCase = new SummarizeEngineeringActivityUseCase(
    deps.github,
    injectionGuard,
  );
  const computeInsightsUseCase = new ComputeRepoInsightsUseCase(deps.github, insightsCalculator);
  const searchIssuesUseCase = new SearchIssuesAndPrsUseCase(deps.github, injectionGuard);
  const createCommentUseCase = new CreateIssueCommentUseCase(deps.github);
  const createIssueUseCase = new CreateIssueUseCase(deps.github);
  const addLabelsUseCase = new AddPullRequestLabelsUseCase(deps.github);

  const reconciliationStore =
    deps.reconciliationStore ??
    (isWriteReconciliationStore(deps.auditStore) ? deps.auditStore : undefined);

  const reconcileWriteUseCase = reconciliationStore
    ? new ReconcileWriteOutcomeUseCase(deps.github, reconciliationStore, deps.permissionPolicy)
    : undefined;

  const orchestrator = new ToolExecutionOrchestrator({
    permissionPolicy: deps.permissionPolicy,
    confirmationService: deps.confirmationService,
    auditStore: deps.auditStore,
    reconciliationStore,
    logger: deps.logger,
    metrics: deps.metrics,
    defaultTimeoutMs: deps.toolTimeoutMs ?? 30_000,
  });

  // ---------------------------------------------------------------------------
  // READ / ANALYSIS TOOLS (readOnlyHint: true)
  // ---------------------------------------------------------------------------

  server.registerTool(
    'analyze_pull_request',
    {
      title: 'Analyze Pull Request Risk & Ownership',
      description:
        'READ-ONLY: Analyzes a GitHub pull request for diff size, sensitive paths, missing tests, review staleness, failing CI checks, CODEOWNERS matches, and prompt-injection attempts.',
      inputSchema: AnalyzePullRequestInputSchema.shape,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async (args) => {
      const response = await orchestrator.execute(
        {
          toolName: 'analyze_pull_request',
          permissionClass: 'READ',
          schema: AnalyzePullRequestInputSchema,
          extractRepo: (i) => ({ owner: i.owner, repo: i.repo }),
          extractInjectionCount: (out) => out.injectionWarnings.length,
          summarizeOutcome: (out) =>
            `Analyzed PR #${out.pullRequestNumber}: risk=${out.riskLevel} (${out.riskScore}/100), flags=${out.riskFlags.length}`,
          execute: (i: AnalyzePullRequestInput) => analyzePrUseCase.execute(i),
        },
        args,
      );
      return toMcpToolResult(response);
    },
  );

  server.registerTool(
    'investigate_ci_failures',
    {
      title: 'Investigate CI Workflow Failures',
      description:
        'READ-ONLY: Inspects recent GitHub Actions workflow runs and failed jobs, groups recurring failure signatures, correlates suspicious commits, and fences untrusted log excerpts.',
      inputSchema: InvestigateCiFailuresInputSchema.shape,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async (args) => {
      const response = await orchestrator.execute(
        {
          toolName: 'investigate_ci_failures',
          permissionClass: 'READ',
          schema: InvestigateCiFailuresInputSchema,
          extractRepo: (i) => ({ owner: i.owner, repo: i.repo }),
          extractInjectionCount: (out) => out.injectionWarnings.length,
          summarizeOutcome: (out) =>
            `Diagnosed ${out.failedRuns}/${out.analyzedRuns} failed runs across ${out.recurringFailures.length} failure group(s)`,
          execute: (i: InvestigateCiFailuresInput) => investigateCiUseCase.execute(i),
        },
        args,
      );
      return toMcpToolResult(response);
    },
  );

  server.registerTool(
    'read_engineering_activity',
    {
      title: 'Summarize Engineering Activity',
      description:
        'READ-ONLY: Summarizes recent commits, merged pull requests, opened/closed issues, and published releases over a bounded time window.',
      inputSchema: SummarizeEngineeringActivityInputSchema.shape,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async (args) => {
      const response = await orchestrator.execute(
        {
          toolName: 'read_engineering_activity',
          permissionClass: 'READ',
          schema: SummarizeEngineeringActivityInputSchema,
          extractRepo: (i) => ({ owner: i.owner, repo: i.repo }),
          extractInjectionCount: (out) => out.injectionWarnings.length,
          summarizeOutcome: (out) =>
            `Summarized ${out.windowDays}d activity: ${out.totals.commits} commits, ${out.totals.mergedPullRequests} merged PRs`,
          execute: (i: SummarizeEngineeringActivityInput) => summarizeActivityUseCase.execute(i),
        },
        args,
      );
      return toMcpToolResult(response);
    },
  );

  server.registerTool(
    'read_repo_insights',
    {
      title: 'Compute Repository Health Insights',
      description:
        'READ-ONLY: Computes repository health metrics including PR cycle time, stale PRs, contributor concentration / bus factor, CI pass rate, issue backlog ratio, and release cadence.',
      inputSchema: ComputeRepoInsightsInputSchema.shape,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async (args) => {
      const response = await orchestrator.execute(
        {
          toolName: 'read_repo_insights',
          permissionClass: 'READ',
          schema: ComputeRepoInsightsInputSchema,
          extractRepo: (i) => ({ owner: i.owner, repo: i.repo }),
          summarizeOutcome: (out) =>
            `Computed repo health: ${out.healthStatus} (${out.healthScore}/100)`,
          execute: (i: ComputeRepoInsightsInput) => computeInsightsUseCase.execute(i),
        },
        args,
      );
      return toMcpToolResult(response);
    },
  );

  server.registerTool(
    'read_search_issues_and_prs',
    {
      title: 'Search Repository Issues & Pull Requests',
      description:
        'READ-ONLY: Searches and paginates repository issues and pull requests with state, kind, label, and keyword filters, returning sanitized and fenced content.',
      inputSchema: SearchIssuesAndPrsInputSchema.shape,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async (args) => {
      const response = await orchestrator.execute(
        {
          toolName: 'read_search_issues_and_prs',
          permissionClass: 'READ',
          schema: SearchIssuesAndPrsInputSchema,
          extractRepo: (i) => ({ owner: i.owner, repo: i.repo }),
          extractInjectionCount: (out) => out.injectionWarnings.length,
          summarizeOutcome: (out) =>
            `Matched ${out.pagination.totalMatched} item(s), returned ${out.items.length}`,
          execute: (i: SearchIssuesAndPrsInput) => searchIssuesUseCase.execute(i),
        },
        args,
      );
      return toMcpToolResult(response);
    },
  );

  server.registerTool(
    'read_reconcile_write_outcome',
    {
      title: 'Reconcile Ambiguous Write Outcome',
      description:
        'READ-ONLY: Inspects GitHub upstream state for an AMBIGUOUS_WRITE_OUTCOME correlationId (e.g., timeout or lost response during a write) to verify whether the comment, issue, or labels actually took effect before deciding whether to re-issue.',
      inputSchema: ReconcileWriteOutcomeInputSchema.shape,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async (args) => {
      const response = await orchestrator.execute(
        {
          toolName: 'read_reconcile_write_outcome',
          permissionClass: 'READ',
          schema: ReconcileWriteOutcomeInputSchema,
          execute: async (i: ReconcileWriteOutcomeInput, ctx: { signal: AbortSignal }) => {
            if (!reconcileWriteUseCase) {
              throw new Error('Write reconciliation store is not configured.');
            }
            return reconcileWriteUseCase.execute(i, { signal: ctx.signal });
          },
          summarizeOutcome: (out) =>
            `Reconciled ${out.correlationId}: ${out.reconciledStatus} (${out.repository})`,
        },
        args,
      );
      return toMcpToolResult(response);
    },
  );

  // ---------------------------------------------------------------------------
  // WRITE TOOLS (require PERMISSION_MODE=write-with-confirmation + 2-phase token)
  // ---------------------------------------------------------------------------

  server.registerTool(
    'write_create_issue_comment',
    {
      title: 'Create Issue or PR Comment (Confirmed Write)',
      description:
        'WRITE (REQUIRES CONFIRMATION): Posts a comment on a GitHub issue or pull request. Blocked in read-only mode. First call returns CONFIRMATION_REQUIRED with a single-use token; re-call with identical arguments and confirmationToken to execute.',
      inputSchema: CreateIssueCommentInputSchema.shape,
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: false,
        openWorldHint: true,
      },
    },
    async (args) => {
      const response = await orchestrator.execute(
        {
          toolName: 'write_create_issue_comment',
          permissionClass: 'WRITE',
          schema: CreateIssueCommentInputSchema,
          extractRepo: (i) => ({ owner: i.owner, repo: i.repo }),
          buildActionPreview: (i) =>
            `Post comment (${i.body.length} chars) on ${i.owner}/${i.repo}#${i.issueNumber}`,
          buildReconciliationSeed: (i) => ({
            toolName: 'write_create_issue_comment',
            targetNumber: i.issueNumber,
          }),
          execute: (i: CreateIssueCommentInput, ctx: { correlationId: string; signal: AbortSignal }) =>
            createCommentUseCase.execute(i, {
              signal: ctx.signal,
              correlationId: ctx.correlationId,
            }),
          summarizeOutcome: (out) =>
            `Created comment #${out.comment.id} on ${out.repository}#${out.issueNumber}`,
        },
        args,
      );
      return toMcpToolResult(response);
    },
  );

  server.registerTool(
    'write_create_issue',
    {
      title: 'Create GitHub Issue (Confirmed Write)',
      description:
        'WRITE (REQUIRES CONFIRMATION): Opens a new GitHub issue. Blocked in read-only mode. First call returns CONFIRMATION_REQUIRED with a single-use token; re-call with identical arguments and confirmationToken to execute.',
      inputSchema: CreateIssueInputSchema.shape,
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: false,
        openWorldHint: true,
      },
    },
    async (args) => {
      const response = await orchestrator.execute(
        {
          toolName: 'write_create_issue',
          permissionClass: 'WRITE',
          schema: CreateIssueInputSchema,
          extractRepo: (i) => ({ owner: i.owner, repo: i.repo }),
          buildActionPreview: (i) =>
            `Create issue '${i.title}' in ${i.owner}/${i.repo} with labels [${i.labels.join(', ')}]`,
          buildReconciliationSeed: () => ({
            toolName: 'write_create_issue',
            targetNumber: null,
          }),
          execute: (i: CreateIssueInput, ctx: { correlationId: string; signal: AbortSignal }) =>
            createIssueUseCase.execute(i, {
              signal: ctx.signal,
              correlationId: ctx.correlationId,
            }),
          summarizeOutcome: (out) => `Created issue #${out.issue.number} in ${out.repository}`,
        },
        args,
      );
      return toMcpToolResult(response);
    },
  );

  server.registerTool(
    'write_add_pull_request_labels',
    {
      title: 'Add Labels to Pull Request (Confirmed Write)',
      description:
        'WRITE (REQUIRES CONFIRMATION): Applies labels to a GitHub pull request. Blocked in read-only mode. First call returns CONFIRMATION_REQUIRED with a single-use token; re-call with identical arguments and confirmationToken to execute.',
      inputSchema: AddPullRequestLabelsInputSchema.shape,
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async (args) => {
      const response = await orchestrator.execute(
        {
          toolName: 'write_add_pull_request_labels',
          permissionClass: 'WRITE',
          schema: AddPullRequestLabelsInputSchema,
          extractRepo: (i) => ({ owner: i.owner, repo: i.repo }),
          buildActionPreview: (i) =>
            `Add labels [${i.labels.join(', ')}] to ${i.owner}/${i.repo}#${i.pullNumber}`,
          buildReconciliationSeed: (i) => ({
            toolName: 'write_add_pull_request_labels',
            targetNumber: i.pullNumber,
            expectedLabels: i.labels,
          }),
          execute: (i: AddPullRequestLabelsInput, ctx: { signal: AbortSignal }) =>
            addLabelsUseCase.execute(i, { signal: ctx.signal }),
          summarizeOutcome: (out) =>
            `Applied labels [${out.appliedLabels.join(', ')}] to ${out.repository}#${out.pullNumber}`,
        },
        args,
      );
      return toMcpToolResult(response);
    },
  );

  // ---------------------------------------------------------------------------
  // MCP RESOURCES
  // ---------------------------------------------------------------------------

  server.registerResource(
    'repo-overview',
    new ResourceTemplate('engineering://repo/{owner}/{repo}/overview', { list: undefined }),
    {
      title: 'Repository Engineering Overview',
      description: 'Sanitized repository metadata, open pull request count, and latest release.',
      mimeType: 'application/json',
    },
    async (uri, variables) => {
      const owner = String(variables.owner ?? '');
      const repo = String(variables.repo ?? '');

      deps.permissionPolicy.assertAllowed({
        toolName: 'resource:repo-overview',
        permissionClass: 'READ',
        owner,
        repo,
      });

      const [repository, openPrs, releases] = await Promise.all([
        deps.github.getRepository(owner, repo),
        deps.github.listPullRequests(owner, repo, { state: 'open', limit: 20 }),
        deps.github.listReleases(owner, repo, 3),
      ]);

      const safeDesc = injectionGuard.inspectAndFence(repository.description ?? '', {
        source: repository.fullName,
        field: 'repository.description',
      });

      const payload = {
        repository: {
          fullName: repository.fullName,
          defaultBranch: repository.defaultBranch,
          visibility: repository.visibility,
          openIssuesCount: repository.openIssuesCount,
          stargazersCount: repository.stargazersCount,
          pushedAt: repository.pushedAt,
          url: repository.url,
          fencedDescription: safeDesc.fenced,
        },
        openPullRequestsCount: openPrs.length,
        latestRelease: releases[0]
          ? {
              tagName: releases[0].tagName,
              publishedAt: releases[0].publishedAt,
              prerelease: releases[0].prerelease,
            }
          : null,
        injectionWarnings: safeDesc.warnings,
      };

      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(payload, null, 2),
          },
        ],
      };
    },
  );

  server.registerResource(
    'audit-recent',
    'engineering://audit/recent',
    {
      title: 'Recent Audit Log Entries',
      description: 'Most recent 25 secret-redacted tool invocation audit records persisted in SQLite.',
      mimeType: 'application/json',
    },
    async (uri) => {
      const records = deps.auditStore.listRecent({ limit: 25 });
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(
              {
                totalRecords: deps.auditStore.count(),
                returned: records.length,
                records,
              },
              null,
              2,
            ),
          },
        ],
      };
    },
  );

  server.registerResource(
    'server-health',
    'engineering://health',
    {
      title: 'MCP Server Health & Metrics Snapshot',
      description: 'Live operational health, permission mode, GitHub client status, and metrics snapshot.',
      mimeType: 'application/json',
    },
    async (uri) => {
      const payload = {
        health: deps.getHealth(),
        metrics: deps.metrics.getSnapshot(),
      };
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(payload, null, 2),
          },
        ],
      };
    },
  );

  return { server, orchestrator };
}

function toMcpToolResult<T>(response: ToolExecutionResponse<T>): CallToolResult {
  return {
    isError: !response.ok,
    content: [
      {
        type: 'text',
        text: JSON.stringify(response, null, 2),
      },
    ],
  };
}

function isWriteReconciliationStore(
  store: AuditStorePort,
): store is AuditStorePort & WriteReconciliationStorePort {
  return (
    typeof (store as unknown as WriteReconciliationStorePort).saveWriteReconciliation ===
      'function' &&
    typeof (store as unknown as WriteReconciliationStorePort).findWriteReconciliation === 'function'
  );
}
