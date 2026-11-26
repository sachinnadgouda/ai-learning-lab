import {
  CiFailureDiagnosis,
  CommitSummary,
  InjectionWarning,
  RecurringFailureGroup,
  WorkflowJob,
  WorkflowRun,
} from './entities.js';
import { PromptInjectionGuard } from './promptInjectionGuard.js';

export interface CiDiagnosisInput {
  repository: string;
  branchFilter: string | null;
  runs: WorkflowRun[];
  jobsByRunId: Map<number, WorkflowJob[]>;
  recentCommits: CommitSummary[];
}

export class CiFailureAnalyzer {
  private readonly injectionGuard: PromptInjectionGuard;

  constructor(injectionGuard = new PromptInjectionGuard()) {
    this.injectionGuard = injectionGuard;
  }

  public diagnose(input: CiDiagnosisInput): CiFailureDiagnosis {
    const failedRuns = input.runs.filter(
      (run) =>
        run.conclusion === 'failure' ||
        run.conclusion === 'timed_out' ||
        run.conclusion === 'cancelled',
    );
    const failureRatePercent =
      input.runs.length === 0
        ? 0
        : Math.round((failedRuns.length / input.runs.length) * 1000) / 10;

    const injectionWarnings: InjectionWarning[] = [];
    const groupMap = new Map<
      string,
      {
        workflowName: string;
        jobName: string;
        failedStep: string;
        occurrences: number;
        branches: Set<string>;
        sampleRunIds: number[];
        excerpt: string;
      }
    >();

    for (const run of failedRuns) {
      const jobs = input.jobsByRunId.get(run.id) ?? [];
      const failedJobs = jobs.filter(
        (j) => j.conclusion === 'failure' || j.conclusion === 'timed_out',
      );

      if (failedJobs.length === 0) {
        const key = `${run.name}|unknown-job|unknown-step`;
        const entry = groupMap.get(key) ?? {
          workflowName: run.name,
          jobName: 'unknown-job',
          failedStep: 'unknown-step',
          occurrences: 0,
          branches: new Set<string>(),
          sampleRunIds: [],
          excerpt: `Workflow run ${run.id} concluded with '${run.conclusion ?? 'unknown'}'.`,
        };
        entry.occurrences += 1;
        entry.branches.add(run.branch);
        if (entry.sampleRunIds.length < 5) entry.sampleRunIds.push(run.id);
        groupMap.set(key, entry);
        continue;
      }

      for (const job of failedJobs) {
        const failedStep =
          job.steps.find((s) => s.conclusion === 'failure' || s.conclusion === 'timed_out')?.name ??
          'job-level-failure';
        const logCheck = this.injectionGuard.inspectAndFence(
          job.logExcerpt ?? `Step '${failedStep}' failed in job '${job.name}'.`,
          {
            source: `${input.repository}:run:${run.id}`,
            field: `workflow.job.${job.id}.log`,
          },
        );
        injectionWarnings.push(...logCheck.warnings);

        const key = `${run.name}|${job.name}|${failedStep}`;
        const entry = groupMap.get(key) ?? {
          workflowName: run.name,
          jobName: job.name,
          failedStep,
          occurrences: 0,
          branches: new Set<string>(),
          sampleRunIds: [],
          excerpt: logCheck.fenced,
        };
        entry.occurrences += 1;
        entry.branches.add(run.branch);
        if (entry.sampleRunIds.length < 5) entry.sampleRunIds.push(run.id);
        groupMap.set(key, entry);
      }
    }

    const recurringFailures: RecurringFailureGroup[] = Array.from(groupMap.values())
      .sort((a, b) => b.occurrences - a.occurrences)
      .map((entry) => ({
        workflowName: entry.workflowName,
        jobName: entry.jobName,
        failedStep: entry.failedStep,
        occurrences: entry.occurrences,
        affectedBranches: Array.from(entry.branches).sort(),
        sampleRunIds: entry.sampleRunIds,
        likelyCategory: classifyFailureCategory(entry.jobName, entry.failedStep, entry.excerpt),
        safeDiagnosticExcerpt: entry.excerpt,
      }));

    // Correlate failed runs to recent commits by headSha
    const failedRunsBySha = new Map<string, number[]>();
    for (const run of failedRuns) {
      const list = failedRunsBySha.get(run.headSha) ?? [];
      list.push(run.id);
      failedRunsBySha.set(run.headSha, list);
    }

    const recentSuspiciousCommits = input.recentCommits
      .filter((commit) => failedRunsBySha.has(commit.sha))
      .map((commit) => {
        const checked = this.injectionGuard.inspectAndFence(commit.message, {
          source: `${input.repository}:commit:${commit.sha.slice(0, 7)}`,
          field: 'commit.message',
        });
        injectionWarnings.push(...checked.warnings);
        return {
          sha: commit.sha,
          author: commit.author,
          authoredAt: commit.authoredAt,
          fencedMessage: checked.fenced,
          linkedFailedRunIds: failedRunsBySha.get(commit.sha) ?? [],
        };
      });

    const recommendedActions = buildCiRecommendations(recurringFailures, failureRatePercent);

    return {
      repository: input.repository,
      branchFilter: input.branchFilter,
      analyzedRuns: input.runs.length,
      failedRuns: failedRuns.length,
      failureRatePercent,
      recurringFailures,
      recentSuspiciousCommits,
      recommendedActions,
      injectionWarnings,
    };
  }
}

function classifyFailureCategory(
  jobName: string,
  stepName: string,
  excerpt: string,
): RecurringFailureGroup['likelyCategory'] {
  const haystack = `${jobName} ${stepName} ${excerpt}`.toLowerCase();
  if (/timed?\s*out|etimedout|econnreset|runner lost|out of memory|oom|502|503/.test(haystack)) {
    return 'INFRA_OR_TIMEOUT';
  }
  if (/eslint|lint|prettier|typecheck|tsc|ts\d{4}|mypy|ruff|checkstyle/.test(haystack)) {
    return 'LINT_OR_TYPECHECK';
  }
  if (/trivy|codeql|snyk|audit|secret|policy|vulnerability|cve/.test(haystack)) {
    return 'SECURITY_OR_POLICY';
  }
  if (/test|vitest|jest|pytest|junit|assert|expect\(|spec failed/.test(haystack)) {
    return 'TEST_FAILURE';
  }
  if (/npm ci|install|compile|build|gradle|maven|docker build|module not found/.test(haystack)) {
    return 'BUILD_OR_DEPENDENCY';
  }
  return 'UNKNOWN';
}

function buildCiRecommendations(
  groups: RecurringFailureGroup[],
  failureRatePercent: number,
): string[] {
  const recs: string[] = [];
  if (groups.length === 0) {
    return ['No failing workflow runs observed in the analyzed window.'];
  }

  const top = groups[0]!;
  recs.push(
    `Prioritize '${top.workflowName} / ${top.jobName}' (step '${top.failedStep}', ${top.occurrences} failure(s), category: ${top.likelyCategory}).`,
  );

  const categories = new Set(groups.map((g) => g.likelyCategory));
  if (categories.has('TEST_FAILURE')) {
    recs.push('Reproduce failing test assertions locally and check for flaky timing or fixture drift.');
  }
  if (categories.has('INFRA_OR_TIMEOUT')) {
    recs.push('Inspect runner timeouts, network retries, or memory limits on failing jobs.');
  }
  if (categories.has('BUILD_OR_DEPENDENCY')) {
    recs.push('Verify lockfile consistency, pinned dependencies, and container base image availability.');
  }
  if (categories.has('LINT_OR_TYPECHECK')) {
    recs.push('Run local lint and typecheck gates before pushing to catch regressions early.');
  }
  if (failureRatePercent >= 30) {
    recs.push(`CI failure rate is elevated (${failureRatePercent}%); consider pausing non-urgent merges until main is green.`);
  }
  return recs;
}
