import { RepoHealthInsights } from '../../domain/entities.js';
import { RepoInsightsCalculator } from '../../domain/repoInsightsCalculator.js';
import { GitHubPort } from '../ports.js';
import { ComputeRepoInsightsInput } from '../schemas.js';

export class ComputeRepoInsightsUseCase {
  constructor(
    private readonly github: GitHubPort,
    private readonly calculator: RepoInsightsCalculator,
  ) {}

  public async execute(input: ComputeRepoInsightsInput, now = new Date()): Promise<RepoHealthInsights> {
    const repository = `${input.owner}/${input.repo}`;
    const sinceIso = new Date(now.getTime() - input.windowDays * 86_400_000).toISOString();

    const [pullRequests, commits, workflowRuns, issues, releases] = await Promise.all([
      this.github.listPullRequests(input.owner, input.repo, { state: 'all', limit: 60 }),
      this.github.listCommits(input.owner, input.repo, { sinceIso, limit: 100 }),
      this.github.listWorkflowRuns(input.owner, input.repo, { limit: 30 }),
      this.github.listIssues(input.owner, input.repo, { state: 'all', limit: 60 }),
      this.github.listReleases(input.owner, input.repo, 15),
    ]);

    return this.calculator.compute({
      repository,
      windowDays: input.windowDays,
      stalePrDays: input.stalePrDays,
      pullRequests,
      commits,
      workflowRuns,
      issues,
      releases,
      now,
    });
  }
}
