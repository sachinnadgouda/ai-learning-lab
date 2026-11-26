import { PullRequestAnalysis } from '../../domain/entities.js';
import { PullRequestRiskAnalyzer } from '../../domain/prRiskAnalyzer.js';
import { AiSummarizerPort, GitHubPort } from '../ports.js';
import { AnalyzePullRequestInput } from '../schemas.js';

export class AnalyzePullRequestUseCase {
  constructor(
    private readonly github: GitHubPort,
    private readonly analyzer: PullRequestRiskAnalyzer,
    private readonly aiSummarizer: AiSummarizerPort,
  ) {}

  public async execute(input: AnalyzePullRequestInput): Promise<PullRequestAnalysis> {
    const repository = `${input.owner}/${input.repo}`;
    const pr = await this.github.getPullRequest(input.owner, input.repo, input.pullNumber);

    const [files, reviews, checkRuns, codeOwnersContent] = await Promise.all([
      this.github.listPullRequestFiles(input.owner, input.repo, input.pullNumber),
      this.github.listPullRequestReviews(input.owner, input.repo, input.pullNumber),
      this.github.listCheckRunsForRef(input.owner, input.repo, pr.headSha),
      this.github.getCodeOwners(input.owner, input.repo, pr.baseBranch),
    ]);

    const analysis = this.analyzer.analyze({
      repository,
      pullRequest: pr,
      files,
      reviews,
      checkRuns,
      codeOwnersContent,
    });

    if (input.includeAiSummary) {
      const deterministicFallback = {
        executiveSummary: `PR #${analysis.pullRequestNumber} (${analysis.riskLevel} risk, score ${analysis.riskScore}/100) modifies ${analysis.diffStats.changedFiles} file(s) with ${analysis.diffStats.totalLinesChanged} changed line(s) and ${analysis.riskFlags.length} flagged risk(s).`,
        keyFindings: analysis.riskFlags.map((f) => `[${f.severity.toUpperCase()}] ${f.code}: ${f.message}`),
        suggestedNextSteps: analysis.recommendations,
      };

      analysis.aiSummary = await this.aiSummarizer.summarize({
        task: 'PR_ANALYSIS',
        structuredContext: {
          repository: analysis.repository,
          pullRequestNumber: analysis.pullRequestNumber,
          riskScore: analysis.riskScore,
          riskLevel: analysis.riskLevel,
          diffStats: analysis.diffStats,
          riskFlags: analysis.riskFlags,
          codeOwners: analysis.codeOwners,
          reviewSummary: analysis.reviewSummary,
          checkSummary: analysis.checkSummary,
          injectionWarningsCount: analysis.injectionWarnings.length,
        },
        fencedUntrustedSnippets: [analysis.fencedTitle, analysis.fencedBody],
        deterministicFallback,
      });
    }

    return analysis;
  }
}
