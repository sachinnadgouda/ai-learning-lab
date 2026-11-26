import { CiFailureDiagnosis, WorkflowJob } from '../../domain/entities.js';
import { CiFailureAnalyzer } from '../../domain/ciFailureAnalyzer.js';
import { AiSummarizerPort, GitHubPort } from '../ports.js';
import { InvestigateCiFailuresInput } from '../schemas.js';

export class InvestigateCiFailuresUseCase {
  constructor(
    private readonly github: GitHubPort,
    private readonly analyzer: CiFailureAnalyzer,
    private readonly aiSummarizer: AiSummarizerPort,
  ) {}

  public async execute(input: InvestigateCiFailuresInput): Promise<CiFailureDiagnosis> {
    const repository = `${input.owner}/${input.repo}`;
    const [runs, recentCommits] = await Promise.all([
      this.github.listWorkflowRuns(input.owner, input.repo, {
        branch: input.branch,
        limit: input.maxRuns,
      }),
      this.github.listCommits(input.owner, input.repo, { limit: 25 }),
    ]);

    const failedRuns = runs
      .filter(
        (r) => r.conclusion === 'failure' || r.conclusion === 'timed_out' || r.conclusion === 'cancelled',
      )
      .slice(0, 10);

    const jobsEntries = await Promise.all(
      failedRuns.map(async (run): Promise<[number, WorkflowJob[]]> => {
        const jobs = await this.github.listWorkflowJobs(input.owner, input.repo, run.id);
        return [run.id, jobs];
      }),
    );
    const jobsByRunId = new Map<number, WorkflowJob[]>(jobsEntries);

    const diagnosis = this.analyzer.diagnose({
      repository,
      branchFilter: input.branch ?? null,
      runs,
      jobsByRunId,
      recentCommits,
    });

    if (input.includeAiSummary) {
      const deterministicFallback = {
        executiveSummary:
          diagnosis.failedRuns === 0
            ? `All ${diagnosis.analyzedRuns} analyzed workflow run(s) succeeded.`
            : `${diagnosis.failedRuns} of ${diagnosis.analyzedRuns} analyzed workflow runs failed (${diagnosis.failureRatePercent}% failure rate) across ${diagnosis.recurringFailures.length} distinct failure signature(s).`,
        keyFindings: diagnosis.recurringFailures.map(
          (g) =>
            `${g.workflowName} / ${g.jobName} failed at '${g.failedStep}' ${g.occurrences}x (${g.likelyCategory})`,
        ),
        suggestedNextSteps: diagnosis.recommendedActions,
      };

      diagnosis.aiSummary = await this.aiSummarizer.summarize({
        task: 'CI_DIAGNOSIS',
        structuredContext: {
          repository: diagnosis.repository,
          branchFilter: diagnosis.branchFilter,
          analyzedRuns: diagnosis.analyzedRuns,
          failedRuns: diagnosis.failedRuns,
          failureRatePercent: diagnosis.failureRatePercent,
          recurringFailures: diagnosis.recurringFailures.map((g) => ({
            workflowName: g.workflowName,
            jobName: g.jobName,
            failedStep: g.failedStep,
            occurrences: g.occurrences,
            likelyCategory: g.likelyCategory,
            affectedBranches: g.affectedBranches,
          })),
          recentSuspiciousCommitsCount: diagnosis.recentSuspiciousCommits.length,
        },
        fencedUntrustedSnippets: [
          ...diagnosis.recurringFailures.slice(0, 3).map((g) => g.safeDiagnosticExcerpt),
          ...diagnosis.recentSuspiciousCommits.slice(0, 3).map((c) => c.fencedMessage),
        ],
        deterministicFallback,
      });
    }

    return diagnosis;
  }
}
