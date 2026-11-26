import { InjectionWarning } from '../../domain/entities.js';
import { PromptInjectionGuard } from '../../domain/promptInjectionGuard.js';
import { GitHubPort } from '../ports.js';
import { SummarizeEngineeringActivityInput } from '../schemas.js';

export interface EngineeringActivitySummary {
  repository: string;
  windowDays: number;
  sinceIso: string;
  totals: {
    commits: number;
    mergedPullRequests: number;
    openedIssues: number;
    closedIssues: number;
    releases: number;
  };
  topAuthors: Array<{ author: string; commits: number }>;
  recentCommits: Array<{
    sha: string;
    author: string;
    authoredAt: string;
    fencedMessage: string;
  }>;
  mergedPullRequests: Array<{
    number: number;
    author: string;
    mergedAt: string;
    fencedTitle: string;
  }>;
  releases: Array<{
    tagName: string;
    publishedAt: string;
    prerelease: boolean;
    fencedName: string;
  }>;
  injectionWarnings: InjectionWarning[];
}

export class SummarizeEngineeringActivityUseCase {
  constructor(
    private readonly github: GitHubPort,
    private readonly injectionGuard: PromptInjectionGuard,
  ) {}

  public async execute(
    input: SummarizeEngineeringActivityInput,
    now = new Date(),
  ): Promise<EngineeringActivitySummary> {
    const repository = `${input.owner}/${input.repo}`;
    const sinceDate = new Date(now.getTime() - input.days * 86_400_000);
    const sinceIso = sinceDate.toISOString();
    const sinceMs = sinceDate.getTime();

    const [commits, pullRequests, issues, releases] = await Promise.all([
      this.github.listCommits(input.owner, input.repo, { sinceIso, limit: 50 }),
      this.github.listPullRequests(input.owner, input.repo, { state: 'closed', limit: 50 }),
      this.github.listIssues(input.owner, input.repo, { state: 'all', sinceIso, limit: 50 }),
      this.github.listReleases(input.owner, input.repo, 20),
    ]);

    const injectionWarnings: InjectionWarning[] = [];

    const filteredCommits = commits.filter((c) => Date.parse(c.authoredAt) >= sinceMs);
    const authorCounts = new Map<string, number>();
    for (const commit of filteredCommits) {
      authorCounts.set(commit.author, (authorCounts.get(commit.author) ?? 0) + 1);
    }

    const recentCommits = filteredCommits.slice(0, 15).map((commit) => {
      const inspected = this.injectionGuard.inspectAndFence(commit.message, {
        source: `${repository}:commit:${commit.sha.slice(0, 7)}`,
        field: 'commit.message',
      });
      injectionWarnings.push(...inspected.warnings);
      return {
        sha: commit.sha,
        author: commit.author,
        authoredAt: commit.authoredAt,
        fencedMessage: inspected.fenced,
      };
    });

    const mergedInWindow = pullRequests.filter(
      (pr) => pr.merged && pr.mergedAt !== null && Date.parse(pr.mergedAt) >= sinceMs,
    );

    const mergedPullRequests = mergedInWindow.slice(0, 15).map((pr) => {
      const inspected = this.injectionGuard.inspectAndFence(pr.title, {
        source: `${repository}#${pr.number}`,
        field: 'pull_request.title',
      });
      injectionWarnings.push(...inspected.warnings);
      return {
        number: pr.number,
        author: pr.author,
        mergedAt: pr.mergedAt!,
        fencedTitle: inspected.fenced,
      };
    });

    const pureIssues = issues.filter((issue) => !issue.isPullRequest);
    const openedIssues = pureIssues.filter((i) => Date.parse(i.createdAt) >= sinceMs).length;
    const closedIssues = pureIssues.filter(
      (i) => i.state === 'closed' && i.closedAt !== null && Date.parse(i.closedAt) >= sinceMs,
    ).length;

    const releasesInWindow = releases.filter(
      (r) => !r.draft && Date.parse(r.publishedAt) >= sinceMs,
    );
    const safeReleases = releasesInWindow.map((rel) => {
      const inspected = this.injectionGuard.inspectAndFence(rel.name || rel.tagName, {
        source: `${repository}:release:${rel.tagName}`,
        field: 'release.name',
      });
      injectionWarnings.push(...inspected.warnings);
      return {
        tagName: rel.tagName,
        publishedAt: rel.publishedAt,
        prerelease: rel.prerelease,
        fencedName: inspected.fenced,
      };
    });

    const topAuthors = Array.from(authorCounts.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([author, count]) => ({ author, commits: count }));

    return {
      repository,
      windowDays: input.days,
      sinceIso,
      totals: {
        commits: filteredCommits.length,
        mergedPullRequests: mergedInWindow.length,
        openedIssues,
        closedIssues,
        releases: releasesInWindow.length,
      },
      topAuthors,
      recentCommits,
      mergedPullRequests,
      releases: safeReleases,
      injectionWarnings,
    };
  }
}
