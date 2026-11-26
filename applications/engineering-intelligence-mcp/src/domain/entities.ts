export type PermissionMode = 'read-only' | 'write-with-confirmation';
export type DeploymentMode = 'local-stdio' | 'shared-server';
export type ToolPermissionClass = 'READ' | 'WRITE';

export interface ExecutionIdentity {
  principalId: string;
  sessionId: string;
  authenticated: boolean;
  authMethod: 'local-process-env' | 'authenticated-principal';
}

export interface Repository {
  owner: string;
  name: string;
  fullName: string;
  description: string | null;
  defaultBranch: string;
  visibility: 'public' | 'private' | 'internal';
  openIssuesCount: number;
  stargazersCount: number;
  pushedAt: string;
  url: string;
}

export interface PullRequest {
  number: number;
  title: string;
  body: string;
  state: 'open' | 'closed';
  draft: boolean;
  merged: boolean;
  author: string;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
  mergedAt: string | null;
  baseBranch: string;
  headBranch: string;
  headSha: string;
  additions: number;
  deletions: number;
  changedFiles: number;
  labels: string[];
  requestedReviewers: string[];
  url: string;
}

export interface PullRequestFile {
  filename: string;
  status: 'added' | 'modified' | 'removed' | 'renamed' | 'copied' | 'changed';
  additions: number;
  deletions: number;
  changes: number;
}

export interface PullRequestReview {
  id: number;
  author: string;
  state: 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED' | 'DISMISSED' | 'PENDING';
  submittedAt: string;
  body: string;
}

export interface CheckRun {
  id: number;
  name: string;
  status: 'queued' | 'in_progress' | 'completed';
  conclusion:
    | 'success'
    | 'failure'
    | 'neutral'
    | 'cancelled'
    | 'skipped'
    | 'timed_out'
    | 'action_required'
    | null;
  startedAt: string | null;
  completedAt: string | null;
  detailsUrl: string | null;
}

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface RiskFlag {
  code:
    | 'LARGE_DIFF'
    | 'HIGH_FILE_COUNT'
    | 'SENSITIVE_PATH'
    | 'MISSING_TESTS'
    | 'STALE_REVIEW'
    | 'UNRESOLVED_CHECKS'
    | 'CHANGES_REQUESTED'
    | 'NO_APPROVALS'
    | 'PROMPT_INJECTION_DETECTED';
  severity: 'low' | 'medium' | 'high';
  message: string;
  evidence?: string[];
}

export interface CodeOwnerMatch {
  pattern: string;
  owners: string[];
  matchedFiles: string[];
}

export interface InjectionWarning {
  field: string;
  category:
    | 'INSTRUCTION_OVERRIDE'
    | 'ROLE_SMUGGLING'
    | 'TOOL_SMUGGLING'
    | 'SECRET_EXFILTRATION'
    | 'HIDDEN_DIRECTIVE'
    | 'UNICODE_OBFUSCATION';
  severity: 'medium' | 'high';
  snippet: string;
}

export interface PullRequestAnalysis {
  repository: string;
  pullRequestNumber: number;
  title: string;
  fencedTitle: string;
  fencedBody: string;
  author: string;
  state: 'open' | 'closed' | 'merged';
  draft: boolean;
  diffStats: {
    additions: number;
    deletions: number;
    totalLinesChanged: number;
    changedFiles: number;
  };
  riskScore: number;
  riskLevel: RiskLevel;
  riskFlags: RiskFlag[];
  codeOwners: CodeOwnerMatch[];
  unownedFiles: string[];
  reviewSummary: {
    approvals: number;
    changesRequested: number;
    commented: number;
    pendingReviewers: string[];
    hoursSinceLastUpdate: number;
  };
  checkSummary: {
    total: number;
    passed: number;
    failed: number;
    pending: number;
    failedChecks: string[];
  };
  recommendations: string[];
  injectionWarnings: InjectionWarning[];
  aiSummary?: AiSummaryResult;
}

export interface Issue {
  number: number;
  title: string;
  body: string;
  state: 'open' | 'closed';
  author: string;
  labels: string[];
  assignees: string[];
  commentsCount: number;
  isPullRequest: boolean;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
  url: string;
}

export interface IssueComment {
  id: number;
  body: string;
  author: string;
  createdAt: string;
  url: string;
}

export interface CommitSummary {
  sha: string;
  message: string;
  author: string;
  authoredAt: string;
  url: string;
}

export interface WorkflowStep {
  name: string;
  status: string;
  conclusion: string | null;
  number: number;
}

export interface WorkflowJob {
  id: number;
  name: string;
  status: string;
  conclusion: string | null;
  startedAt: string | null;
  completedAt: string | null;
  steps: WorkflowStep[];
  logExcerpt?: string;
}

export interface WorkflowRun {
  id: number;
  name: string;
  workflowPath: string;
  branch: string;
  headSha: string;
  event: string;
  status: string;
  conclusion: string | null;
  createdAt: string;
  updatedAt: string;
  url: string;
}

export interface RecurringFailureGroup {
  workflowName: string;
  jobName: string;
  failedStep: string;
  occurrences: number;
  affectedBranches: string[];
  sampleRunIds: number[];
  likelyCategory:
    | 'TEST_FAILURE'
    | 'LINT_OR_TYPECHECK'
    | 'BUILD_OR_DEPENDENCY'
    | 'INFRA_OR_TIMEOUT'
    | 'SECURITY_OR_POLICY'
    | 'UNKNOWN';
  safeDiagnosticExcerpt: string;
}

export interface CiFailureDiagnosis {
  repository: string;
  branchFilter: string | null;
  analyzedRuns: number;
  failedRuns: number;
  failureRatePercent: number;
  recurringFailures: RecurringFailureGroup[];
  recentSuspiciousCommits: Array<{
    sha: string;
    author: string;
    authoredAt: string;
    fencedMessage: string;
    linkedFailedRunIds: number[];
  }>;
  recommendedActions: string[];
  injectionWarnings: InjectionWarning[];
  aiSummary?: AiSummaryResult;
}

export interface ReleaseSummary {
  id: number;
  tagName: string;
  name: string;
  body: string;
  draft: boolean;
  prerelease: boolean;
  author: string;
  publishedAt: string;
  url: string;
}

export interface RepoHealthInsights {
  repository: string;
  windowDays: number;
  computedAt: string;
  prMetrics: {
    openCount: number;
    mergedInWindow: number;
    staleOpenCount: number;
    stalePullRequestNumbers: number[];
    medianCycleTimeHours: number | null;
    averageCycleTimeHours: number | null;
  };
  contributorConcentration: {
    uniqueContributors: number;
    estimatedBusFactor: number;
    topContributorSharePercent: number;
    topContributors: Array<{
      author: string;
      commits: number;
      sharePercent: number;
    }>;
  };
  ciHealth: {
    totalRuns: number;
    successfulRuns: number;
    failedRuns: number;
    passRatePercent: number | null;
  };
  issueBacklog: {
    openIssues: number;
    closedInWindow: number;
    backlogPressureRatio: number | null;
  };
  releaseCadence: {
    releasesInWindow: number;
    latestReleaseTag: string | null;
    daysSinceLastRelease: number | null;
  };
  healthScore: number;
  healthStatus: 'HEALTHY' | 'NEEDS_ATTENTION' | 'AT_RISK';
  highlights: string[];
  risks: string[];
}

export interface AiSummaryResult {
  provider: 'none' | 'ollama' | 'openai-compatible';
  model: string;
  usedFallback: boolean;
  executiveSummary: string;
  keyFindings: string[];
  suggestedNextSteps: string[];
  latencyMs: number;
  promptTokens?: number;
  completionTokens?: number;
}

export interface ConfirmationRecord {
  nonce: string;
  tokenHash: string;
  toolName: string;
  actor: string;
  sessionId: string;
  deploymentMode: DeploymentMode;
  repository: string;
  argsDigest: string;
  actionPreview: string;
  status: 'PENDING' | 'CONSUMED' | 'EXPIRED';
  createdAt: string;
  expiresAt: string;
  consumedAt: string | null;
}

export interface WriteReconciliationRecord {
  correlationId: string;
  toolName: 'write_create_issue_comment' | 'write_create_issue' | 'write_add_pull_request_labels';
  repository: string;
  targetNumber: number | null;
  operationMarker: string;
  expectedLabels: string[];
  actor: string;
  status: 'AMBIGUOUS' | 'CONFIRMED_APPLIED' | 'NOT_FOUND_UPSTREAM';
  createdAt: string;
  resolvedAt: string | null;
  resolutionDetail: string | null;
}

export interface AuditRecord {
  id?: number;
  correlationId: string;
  timestamp: string;
  actor: string;
  toolName: string;
  permissionClass: ToolPermissionClass;
  repository: string | null;
  outcome: 'SUCCESS' | 'DENIED' | 'CONFIRMATION_REQUIRED' | 'AMBIGUOUS' | 'ERROR';
  errorCode: string | null;
  durationMs: number;
  injectionWarningsCount: number;
  redactedArgsJson: string;
  summary: string;
}
