import {
  CommitSummary,
  Issue,
  PullRequest,
  ReleaseSummary,
  RepoHealthInsights,
  WorkflowRun,
} from './entities.js';

export interface ComputeInsightsInput {
  repository: string;
  windowDays: number;
  stalePrDays: number;
  pullRequests: PullRequest[];
  commits: CommitSummary[];
  workflowRuns: WorkflowRun[];
  issues: Issue[];
  releases: ReleaseSummary[];
  now?: Date;
}

export class RepoInsightsCalculator {
  public compute(input: ComputeInsightsInput): RepoHealthInsights {
    const now = input.now ?? new Date();
    const windowStartMs = now.getTime() - input.windowDays * 86_400_000;
    const staleThresholdMs = input.stalePrDays * 86_400_000;

    // PR metrics
    const openPrs = input.pullRequests.filter((pr) => pr.state === 'open');
    const stalePrs = openPrs.filter((pr) => {
      const updated = Date.parse(pr.updatedAt);
      return !Number.isNaN(updated) && now.getTime() - updated >= staleThresholdMs;
    });

    const mergedInWindow = input.pullRequests.filter((pr) => {
      if (!pr.merged || !pr.mergedAt) return false;
      const mergedMs = Date.parse(pr.mergedAt);
      return !Number.isNaN(mergedMs) && mergedMs >= windowStartMs;
    });

    const cycleHours = mergedInWindow
      .map((pr) => {
        const created = Date.parse(pr.createdAt);
        const merged = Date.parse(pr.mergedAt!);
        if (Number.isNaN(created) || Number.isNaN(merged) || merged < created) {
          return null;
        }
        return (merged - created) / 3_600_000;
      })
      .filter((v): v is number => v !== null)
      .sort((a, b) => a - b);

    const medianCycleTimeHours =
      cycleHours.length === 0 ? null : round1(computeMedian(cycleHours));
    const averageCycleTimeHours =
      cycleHours.length === 0
        ? null
        : round1(cycleHours.reduce((sum, h) => sum + h, 0) / cycleHours.length);

    // Contributor concentration & bus factor
    const commitCounts = new Map<string, number>();
    for (const commit of input.commits) {
      const author = commit.author || 'unknown';
      commitCounts.set(author, (commitCounts.get(author) ?? 0) + 1);
    }

    const totalCommits = input.commits.length;
    const rankedContributors = Array.from(commitCounts.entries())
      .sort((a, b) => b[1] - a[1])
      .map(([author, commits]) => ({
        author,
        commits,
        sharePercent: totalCommits === 0 ? 0 : round1((commits / totalCommits) * 100),
      }));

    // Bus factor: minimum number of top contributors accounting for >= 50% of commits
    let cumulative = 0;
    let estimatedBusFactor = 0;
    for (const contributor of rankedContributors) {
      cumulative += contributor.commits;
      estimatedBusFactor += 1;
      if (totalCommits > 0 && cumulative / totalCommits >= 0.5) {
        break;
      }
    }

    const topContributorSharePercent = rankedContributors[0]?.sharePercent ?? 0;

    // CI health
    const completedRuns = input.workflowRuns.filter((r) => r.status === 'completed');
    const successfulRuns = completedRuns.filter((r) => r.conclusion === 'success').length;
    const failedRuns = completedRuns.filter(
      (r) => r.conclusion === 'failure' || r.conclusion === 'timed_out',
    ).length;
    const evaluatedRuns = successfulRuns + failedRuns;
    const passRatePercent =
      evaluatedRuns === 0 ? null : round1((successfulRuns / evaluatedRuns) * 100);

    // Issue backlog (excluding PRs)
    const pureIssues = input.issues.filter((issue) => !issue.isPullRequest);
    const openIssues = pureIssues.filter((issue) => issue.state === 'open').length;
    const closedInWindow = pureIssues.filter((issue) => {
      if (issue.state !== 'closed' || !issue.closedAt) return false;
      const closedMs = Date.parse(issue.closedAt);
      return !Number.isNaN(closedMs) && closedMs >= windowStartMs;
    }).length;
    const backlogPressureRatio =
      closedInWindow === 0 ? (openIssues > 0 ? openIssues : 0) : round1(openIssues / closedInWindow);

    // Release cadence
    const publishedReleases = input.releases
      .filter((r) => !r.draft)
      .sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt));
    const releasesInWindow = publishedReleases.filter(
      (r) => Date.parse(r.publishedAt) >= windowStartMs,
    ).length;
    const latestRelease = publishedReleases[0] ?? null;
    const daysSinceLastRelease =
      latestRelease === null
        ? null
        : Math.max(0, round1((now.getTime() - Date.parse(latestRelease.publishedAt)) / 86_400_000));

    // Composite health score (0-100)
    let score = 100;
    const highlights: string[] = [];
    const risks: string[] = [];

    if (passRatePercent !== null) {
      if (passRatePercent < 75) {
        score -= 25;
        risks.push(`Low CI pass rate (${passRatePercent}%).`);
      } else if (passRatePercent < 90) {
        score -= 10;
        risks.push(`Moderate CI instability (${passRatePercent}% pass rate).`);
      } else {
        highlights.push(`Strong CI pass rate (${passRatePercent}%).`);
      }
    }

    if (stalePrs.length >= 3) {
      score -= 15;
      risks.push(`${stalePrs.length} open PR(s) have been stale for >= ${input.stalePrDays} days.`);
    } else if (stalePrs.length > 0) {
      score -= 6;
      risks.push(`${stalePrs.length} open PR(s) exceed the ${input.stalePrDays}-day staleness threshold.`);
    } else if (openPrs.length > 0) {
      highlights.push('No stale open pull requests.');
    }

    if (totalCommits >= 5 && estimatedBusFactor <= 1 && topContributorSharePercent >= 70) {
      score -= 18;
      risks.push(
        `High contributor concentration (bus factor = ${estimatedBusFactor}, top author = ${topContributorSharePercent}% of commits).`,
      );
    } else if (rankedContributors.length >= 3) {
      highlights.push(
        `Healthy contribution spread across ${rankedContributors.length} contributors (estimated bus factor: ${estimatedBusFactor}).`,
      );
    }

    if (medianCycleTimeHours !== null) {
      if (medianCycleTimeHours > 72) {
        score -= 12;
        risks.push(`Slow PR median cycle time (${medianCycleTimeHours}h).`);
      } else {
        highlights.push(`Responsive PR median cycle time (${medianCycleTimeHours}h).`);
      }
    }

    if (releasesInWindow > 0) {
      highlights.push(`${releasesInWindow} release(s) shipped in the last ${input.windowDays} days.`);
    }

    const boundedScore = Math.max(0, Math.min(100, score));
    const healthStatus: RepoHealthInsights['healthStatus'] =
      boundedScore >= 80 ? 'HEALTHY' : boundedScore >= 55 ? 'NEEDS_ATTENTION' : 'AT_RISK';

    return {
      repository: input.repository,
      windowDays: input.windowDays,
      computedAt: now.toISOString(),
      prMetrics: {
        openCount: openPrs.length,
        mergedInWindow: mergedInWindow.length,
        staleOpenCount: stalePrs.length,
        stalePullRequestNumbers: stalePrs.map((p) => p.number),
        medianCycleTimeHours,
        averageCycleTimeHours,
      },
      contributorConcentration: {
        uniqueContributors: rankedContributors.length,
        estimatedBusFactor,
        topContributorSharePercent,
        topContributors: rankedContributors.slice(0, 5),
      },
      ciHealth: {
        totalRuns: completedRuns.length,
        successfulRuns,
        failedRuns,
        passRatePercent,
      },
      issueBacklog: {
        openIssues,
        closedInWindow,
        backlogPressureRatio,
      },
      releaseCadence: {
        releasesInWindow,
        latestReleaseTag: latestRelease?.tagName ?? null,
        daysSinceLastRelease,
      },
      healthScore: boundedScore,
      healthStatus,
      highlights,
      risks,
    };
  }
}

function computeMedian(sorted: number[]): number {
  const mid = Math.floor(sorted.length / 2);
  if (sorted.length % 2 === 0) {
    return (sorted[mid - 1]! + sorted[mid]!) / 2;
  }
  return sorted[mid]!;
}

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}
