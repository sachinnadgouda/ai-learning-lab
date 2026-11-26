import {
  CheckRun,
  InjectionWarning,
  PullRequest,
  PullRequestAnalysis,
  PullRequestFile,
  PullRequestReview,
  RiskFlag,
  RiskLevel,
} from './entities.js';
import { CodeOwnersParser } from './codeOwnersParser.js';
import { PromptInjectionGuard } from './promptInjectionGuard.js';

const SENSITIVE_PATH_PATTERNS: Array<{ label: string; regex: RegExp }> = [
  { label: 'CI/CD workflow', regex: /^\.github\/workflows\//i },
  { label: 'CODEOWNERS or repo governance', regex: /^\.github\/CODEOWNERS$/i },
  { label: 'Auth/Security code', regex: /(?:^|\/)(?:auth|oauth|security|crypto|rbac|iam|permission|token|secret)/i },
  { label: 'Infrastructure/IaC', regex: /(?:^|\/)(?:terraform|infra|k8s|helm|deploy|Dockerfile|compose\.ya?ml)/i },
  { label: 'Database migration/schema', regex: /(?:^|\/)(?:migrations?|schema\.sql|prisma\/migrations)/i },
  { label: 'Dependency manifest', regex: /(?:^|\/)(?:package(?:-lock)?\.json|pom\.xml|build\.gradle(?:\.kts)?|go\.mod|requirements\.txt|poetry\.lock)$/i },
];

const SOURCE_CODE_REGEX = /\.(?:ts|tsx|js|jsx|py|java|kt|go|rs|cs|rb|php)$/i;
const TEST_FILE_REGEX = /(?:^|\/)(?:tests?|__tests__|spec)\/|\.(?:test|spec)\.[a-z0-9]+$|Test\.(?:java|kt)$|_test\.go$/i;

export interface AnalyzePrInput {
  repository: string;
  pullRequest: PullRequest;
  files: PullRequestFile[];
  reviews: PullRequestReview[];
  checkRuns: CheckRun[];
  codeOwnersContent: string | null;
  now?: Date;
}

export class PullRequestRiskAnalyzer {
  private readonly codeOwnersParser: CodeOwnersParser;
  private readonly injectionGuard: PromptInjectionGuard;

  constructor(
    codeOwnersParser = new CodeOwnersParser(),
    injectionGuard = new PromptInjectionGuard(),
  ) {
    this.codeOwnersParser = codeOwnersParser;
    this.injectionGuard = injectionGuard;
  }

  public analyze(input: AnalyzePrInput): PullRequestAnalysis {
    const now = input.now ?? new Date();
    const pr = input.pullRequest;
    const totalLinesChanged = pr.additions + pr.deletions;
    const riskFlags: RiskFlag[] = [];
    let riskScore = 0;

    // 1. Prompt-injection defense across untrusted PR fields
    const titleInspection = this.injectionGuard.inspectAndFence(pr.title, {
      source: `${input.repository}#${pr.number}`,
      field: 'pull_request.title',
    });
    const bodyInspection = this.injectionGuard.inspectAndFence(pr.body, {
      source: `${input.repository}#${pr.number}`,
      field: 'pull_request.body',
    });

    const injectionWarnings: InjectionWarning[] = [
      ...titleInspection.warnings,
      ...bodyInspection.warnings,
    ];

    for (const review of input.reviews) {
      const reviewCheck = this.injectionGuard.inspectText(
        review.body,
        `pull_request.review.${review.id}`,
      );
      injectionWarnings.push(...reviewCheck.warnings);
    }

    for (const file of input.files) {
      const pathCheck = this.injectionGuard.inspectText(file.filename, 'pull_request.file.filename');
      injectionWarnings.push(...pathCheck.warnings);
    }

    if (injectionWarnings.length > 0) {
      riskScore += 25;
      riskFlags.push({
        code: 'PROMPT_INJECTION_DETECTED',
        severity: 'high',
        message: `Detected ${injectionWarnings.length} potential prompt-injection pattern(s) in untrusted GitHub PR content. All text has been sanitized and fenced.`,
        evidence: injectionWarnings.slice(0, 3).map((w) => `${w.field}: ${w.category}`),
      });
    }

    // 2. Diff size and changed file count
    if (totalLinesChanged >= 1000) {
      riskScore += 25;
      riskFlags.push({
        code: 'LARGE_DIFF',
        severity: 'high',
        message: `Very large diff (${totalLinesChanged} lines changed). High cognitive load for reviewers.`,
      });
    } else if (totalLinesChanged >= 400) {
      riskScore += 14;
      riskFlags.push({
        code: 'LARGE_DIFF',
        severity: 'medium',
        message: `Large diff (${totalLinesChanged} lines changed). Consider splitting into smaller PRs.`,
      });
    }

    if (pr.changedFiles >= 20) {
      riskScore += 15;
      riskFlags.push({
        code: 'HIGH_FILE_COUNT',
        severity: 'high',
        message: `Touches ${pr.changedFiles} files, increasing blast radius and merge conflict risk.`,
      });
    } else if (pr.changedFiles >= 10) {
      riskScore += 8;
      riskFlags.push({
        code: 'HIGH_FILE_COUNT',
        severity: 'medium',
        message: `Touches ${pr.changedFiles} files across the repository.`,
      });
    }

    // 3. Sensitive path analysis
    const sensitiveMatches: string[] = [];
    for (const file of input.files) {
      for (const pattern of SENSITIVE_PATH_PATTERNS) {
        if (pattern.regex.test(file.filename)) {
          sensitiveMatches.push(`${file.filename} (${pattern.label})`);
          break;
        }
      }
    }
    if (sensitiveMatches.length > 0) {
      riskScore += Math.min(25, 12 + sensitiveMatches.length * 4);
      riskFlags.push({
        code: 'SENSITIVE_PATH',
        severity: 'high',
        message: `Modifies ${sensitiveMatches.length} security- or infrastructure-sensitive path(s).`,
        evidence: sensitiveMatches.slice(0, 10),
      });
    }

    // 4. Missing tests heuristic
    const prodSourceFiles = input.files
      .map((f) => f.filename)
      .filter((name) => SOURCE_CODE_REGEX.test(name) && !TEST_FILE_REGEX.test(name));
    const testFiles = input.files
      .map((f) => f.filename)
      .filter((name) => TEST_FILE_REGEX.test(name));

    if (prodSourceFiles.length > 0 && testFiles.length === 0) {
      riskScore += 16;
      riskFlags.push({
        code: 'MISSING_TESTS',
        severity: 'medium',
        message: `Modified ${prodSourceFiles.length} production source file(s) with no accompanying test file changes.`,
        evidence: prodSourceFiles.slice(0, 5),
      });
    }

    // 5. Review status & staleness (latest decisive review per reviewer)
    const latestReviewByAuthor = new Map<string, PullRequestReview>();
    const sortedReviews = [...input.reviews].sort(
      (a, b) => Date.parse(a.submittedAt) - Date.parse(b.submittedAt),
    );
    for (const review of sortedReviews) {
      if (review.state === 'APPROVED' || review.state === 'CHANGES_REQUESTED' || review.state === 'DISMISSED') {
        latestReviewByAuthor.set(review.author, review);
      } else if (!latestReviewByAuthor.has(review.author)) {
        latestReviewByAuthor.set(review.author, review);
      }
    }

    let approvals = 0;
    let changesRequested = 0;
    let commented = 0;
    for (const review of latestReviewByAuthor.values()) {
      if (review.state === 'APPROVED') approvals += 1;
      else if (review.state === 'CHANGES_REQUESTED') changesRequested += 1;
      else if (review.state === 'COMMENTED') commented += 1;
    }

    const updatedMs = Date.parse(pr.updatedAt);
    const hoursSinceLastUpdate = Number.isNaN(updatedMs)
      ? 0
      : Math.max(0, Math.round(((now.getTime() - updatedMs) / 3_600_000) * 10) / 10);

    if (changesRequested > 0) {
      riskScore += 18;
      riskFlags.push({
        code: 'CHANGES_REQUESTED',
        severity: 'high',
        message: `${changesRequested} reviewer(s) currently have CHANGES_REQUESTED.`,
      });
    }

    if (pr.state === 'open' && !pr.draft && approvals === 0) {
      riskScore += 10;
      riskFlags.push({
        code: 'NO_APPROVALS',
        severity: 'medium',
        message: 'Open non-draft PR currently has 0 approvals.',
      });
    }

    if (pr.state === 'open' && !pr.draft && hoursSinceLastUpdate >= 72) {
      riskScore += 10;
      riskFlags.push({
        code: 'STALE_REVIEW',
        severity: 'medium',
        message: `PR has been waiting without activity for ${hoursSinceLastUpdate} hours (>= 72h).`,
      });
    }

    // 6. Check runs
    let passed = 0;
    let failed = 0;
    let pending = 0;
    const failedChecks: string[] = [];

    for (const check of input.checkRuns) {
      if (check.status !== 'completed') {
        pending += 1;
      } else if (check.conclusion === 'success' || check.conclusion === 'skipped' || check.conclusion === 'neutral') {
        passed += 1;
      } else {
        failed += 1;
        failedChecks.push(`${check.name} (${check.conclusion ?? 'unknown'})`);
      }
    }

    if (failed > 0 || pending > 0) {
      riskScore += failed > 0 ? 22 : 8;
      riskFlags.push({
        code: 'UNRESOLVED_CHECKS',
        severity: failed > 0 ? 'high' : 'low',
        message:
          failed > 0
            ? `${failed} CI check(s) failed and ${pending} check(s) are still pending.`
            : `${pending} CI check(s) are still running or queued.`,
        evidence: failedChecks.length > 0 ? failedChecks : undefined,
      });
    }

    // 7. CODEOWNERS matching
    const rules = this.codeOwnersParser.parse(input.codeOwnersContent);
    const { matches: codeOwners, unownedFiles } = this.codeOwnersParser.matchFiles(
      input.files.map((f) => f.filename),
      rules,
    );

    const boundedScore = Math.min(100, riskScore);
    const riskLevel = classifyRiskLevel(boundedScore);
    const recommendations = buildRecommendations({
      riskFlags,
      codeOwners,
      pr,
    });

    return {
      repository: input.repository,
      pullRequestNumber: pr.number,
      title: titleInspection.sanitized,
      fencedTitle: titleInspection.fenced,
      fencedBody: bodyInspection.fenced,
      author: pr.author,
      state: pr.merged ? 'merged' : pr.state,
      draft: pr.draft,
      diffStats: {
        additions: pr.additions,
        deletions: pr.deletions,
        totalLinesChanged,
        changedFiles: pr.changedFiles,
      },
      riskScore: boundedScore,
      riskLevel,
      riskFlags,
      codeOwners,
      unownedFiles,
      reviewSummary: {
        approvals,
        changesRequested,
        commented,
        pendingReviewers: pr.requestedReviewers,
        hoursSinceLastUpdate,
      },
      checkSummary: {
        total: input.checkRuns.length,
        passed,
        failed,
        pending,
        failedChecks,
      },
      recommendations,
      injectionWarnings,
    };
  }
}

function classifyRiskLevel(score: number): RiskLevel {
  if (score >= 70) return 'CRITICAL';
  if (score >= 45) return 'HIGH';
  if (score >= 20) return 'MEDIUM';
  return 'LOW';
}

function buildRecommendations(params: {
  riskFlags: RiskFlag[];
  codeOwners: PullRequestAnalysis['codeOwners'];
  pr: PullRequest;
}): string[] {
  const recs: string[] = [];
  const codes = new Set(params.riskFlags.map((f) => f.code));

  if (codes.has('PROMPT_INJECTION_DETECTED')) {
    recs.push('Treat PR text as adversarial input; manually inspect raw diff before taking any write action.');
  }
  if (codes.has('UNRESOLVED_CHECKS')) {
    recs.push('Resolve failing or pending CI checks before merging.');
  }
  if (codes.has('SENSITIVE_PATH')) {
    const owners = Array.from(new Set(params.codeOwners.flatMap((c) => c.owners)));
    recs.push(
      owners.length > 0
        ? `Require security/domain sign-off from CODEOWNERS: ${owners.join(', ')}.`
        : 'Require explicit security/platform review for sensitive path modifications.',
    );
  }
  if (codes.has('MISSING_TESTS')) {
    recs.push('Add unit or integration test coverage for modified production source files.');
  }
  if (codes.has('LARGE_DIFF') || codes.has('HIGH_FILE_COUNT')) {
    recs.push('Consider breaking the PR into smaller, independently verifiable changes.');
  }
  if (codes.has('CHANGES_REQUESTED')) {
    recs.push('Address open requested changes and re-request reviewer approval.');
  } else if (codes.has('NO_APPROVALS') && params.pr.state === 'open') {
    recs.push('Request review from relevant CODEOWNERS to obtain at least one approval.');
  }
  if (codes.has('STALE_REVIEW')) {
    recs.push('Ping assigned reviewers or rebase the branch to unblock the stale PR.');
  }

  if (recs.length === 0) {
    recs.push('PR meets baseline deterministic quality and risk checks.');
  }
  return recs;
}
