import {
  AiSummaryResult,
  AuditRecord,
  CheckRun,
  CommitSummary,
  ConfirmationRecord,
  Issue,
  IssueComment,
  PullRequest,
  PullRequestFile,
  PullRequestReview,
  ReleaseSummary,
  Repository,
  ToolPermissionClass,
  WorkflowJob,
  WorkflowRun,
  WriteReconciliationRecord,
} from '../domain/entities.js';
import { ConfirmationStore } from '../domain/confirmationService.js';

export interface GitHubRequestContext {
  signal?: AbortSignal;
}

export interface GitHubPort {
  readonly mode: 'demo' | 'live';
  getRepository(owner: string, repo: string, ctx?: GitHubRequestContext): Promise<Repository>;
  getPullRequest(
    owner: string,
    repo: string,
    pullNumber: number,
    ctx?: GitHubRequestContext,
  ): Promise<PullRequest>;
  listPullRequests(
    owner: string,
    repo: string,
    options?: { state?: 'open' | 'closed' | 'all'; limit?: number; signal?: AbortSignal },
  ): Promise<PullRequest[]>;
  listPullRequestFiles(
    owner: string,
    repo: string,
    pullNumber: number,
    ctx?: GitHubRequestContext,
  ): Promise<PullRequestFile[]>;
  listPullRequestReviews(
    owner: string,
    repo: string,
    pullNumber: number,
    ctx?: GitHubRequestContext,
  ): Promise<PullRequestReview[]>;
  listCheckRunsForRef(
    owner: string,
    repo: string,
    ref: string,
    ctx?: GitHubRequestContext,
  ): Promise<CheckRun[]>;
  getCodeOwners(
    owner: string,
    repo: string,
    ref?: string,
    ctx?: GitHubRequestContext,
  ): Promise<string | null>;
  listWorkflowRuns(
    owner: string,
    repo: string,
    options?: { branch?: string; limit?: number; signal?: AbortSignal },
  ): Promise<WorkflowRun[]>;
  listWorkflowJobs(
    owner: string,
    repo: string,
    runId: number,
    ctx?: GitHubRequestContext,
  ): Promise<WorkflowJob[]>;
  listCommits(
    owner: string,
    repo: string,
    options?: { sinceIso?: string; limit?: number; signal?: AbortSignal },
  ): Promise<CommitSummary[]>;
  listIssues(
    owner: string,
    repo: string,
    options?: {
      state?: 'open' | 'closed' | 'all';
      labels?: string[];
      sinceIso?: string;
      limit?: number;
      signal?: AbortSignal;
    },
  ): Promise<Issue[]>;
  listIssueComments(
    owner: string,
    repo: string,
    issueNumber: number,
    options?: { limit?: number; signal?: AbortSignal },
  ): Promise<IssueComment[]>;
  listReleases(
    owner: string,
    repo: string,
    limit?: number,
    ctx?: GitHubRequestContext,
  ): Promise<ReleaseSummary[]>;
  createIssueComment(
    owner: string,
    repo: string,
    issueNumber: number,
    body: string,
    ctx?: GitHubRequestContext,
  ): Promise<IssueComment>;
  createIssue(
    owner: string,
    repo: string,
    input: { title: string; body: string; labels?: string[] },
    ctx?: GitHubRequestContext,
  ): Promise<Issue>;
  addLabelsToPullRequest(
    owner: string,
    repo: string,
    pullNumber: number,
    labels: string[],
    ctx?: GitHubRequestContext,
  ): Promise<string[]>;
  healthCheck(): { healthy: boolean; detail: string; circuitState?: string };
}

export interface AuditStorePort {
  append(record: AuditRecord): void;
  listRecent(options?: { limit?: number; offset?: number; toolName?: string }): AuditRecord[];
  count(): number;
}

export type ConfirmationStorePort = ConfirmationStore & {
  listRecentConfirmations(limit?: number): ConfirmationRecord[];
};

export interface WriteReconciliationStorePort {
  saveWriteReconciliation(record: WriteReconciliationRecord): void;
  findWriteReconciliation(correlationId: string): WriteReconciliationRecord | null;
  resolveWriteReconciliation(
    correlationId: string,
    status: 'CONFIRMED_APPLIED' | 'NOT_FOUND_UPSTREAM',
    resolvedAt: string,
    resolutionDetail: string,
  ): boolean;
  listPendingWriteReconciliations(limit?: number): WriteReconciliationRecord[];
}

export interface AiSummarizerInput {
  task: 'PR_ANALYSIS' | 'CI_DIAGNOSIS';
  structuredContext: Record<string, unknown>;
  fencedUntrustedSnippets: string[];
  deterministicFallback: {
    executiveSummary: string;
    keyFindings: string[];
    suggestedNextSteps: string[];
  };
}

export interface AiSummarizerPort {
  readonly provider: 'none' | 'ollama' | 'openai-compatible';
  readonly model: string;
  summarize(input: AiSummarizerInput): Promise<AiSummaryResult>;
}

export interface LoggerPort {
  debug(message: string, meta?: Record<string, unknown>): void;
  info(message: string, meta?: Record<string, unknown>): void;
  warn(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
}

export interface MetricsPort {
  recordToolExecution(params: {
    toolName: string;
    permissionClass: ToolPermissionClass;
    outcome: AuditRecord['outcome'];
    durationMs: number;
    injectionWarningsCount: number;
  }): void;
  recordUpstreamRequest(params: {
    target: 'github' | 'ai';
    status: string;
    durationMs: number;
  }): void;
  recordRateLimit(remaining: number | null): void;
  renderPrometheus(): string;
  getSnapshot(): Record<string, unknown>;
}
