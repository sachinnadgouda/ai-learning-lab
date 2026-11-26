import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CiFailureAnalyzer } from '../domain/ciFailureAnalyzer.js';
import { PullRequestRiskAnalyzer } from '../domain/prRiskAnalyzer.js';
import { PromptInjectionGuard } from '../domain/promptInjectionGuard.js';
import { RiskLevel } from '../domain/entities.js';
import {
  HttpAiSummarizer,
  parseAndValidateLlmJson,
} from '../infrastructure/ai/HttpAiSummarizer.js';
import { JsonStderrLogger } from '../infrastructure/observability/logger.js';
import { InMemoryMetrics } from '../infrastructure/observability/metrics.js';

interface EvalDataset {
  version: string;
  thresholds: {
    minInjectionRecall: number;
    minInjectionPrecision: number;
    minSchemaAccuracy: number;
    minPrDiagnosisAccuracy: number;
    minCiDiagnosisAccuracy: number;
  };
  promptInjectionCases: Array<{
    id: string;
    text: string;
    expectedInjection: boolean;
    expectedCategory?: string;
  }>;
  llmOutputSchemaCases: Array<{
    id: string;
    rawOutput: string;
    expectedValid: boolean;
  }>;
  deterministicPrRiskCases: Array<{
    id: string;
    title: string;
    body: string;
    additions: number;
    deletions: number;
    files: string[];
    approvals: number;
    failedChecks: number;
    expectedMinRiskLevel: RiskLevel;
    expectedMaxRiskLevel?: RiskLevel;
    expectedRequiredFlags: string[];
  }>;
  deterministicCiDiagnosisCases: Array<{
    id: string;
    jobName: string;
    failedStep: string;
    logExcerpt: string;
    expectedCategory: string;
  }>;
}

export interface EvaluationReport {
  datasetVersion: string;
  promptInjection: {
    totalCases: number;
    truePositives: number;
    falsePositives: number;
    trueNegatives: number;
    falseNegatives: number;
    recall: number;
    precision: number;
    f1: number;
    fenceBreakoutBlocked: boolean;
  };
  llmStructuredOutput: {
    totalCases: number;
    passed: number;
    accuracy: number;
    fallbackRecoveryVerified: boolean;
  };
  deterministicPrDiagnosis: {
    totalCases: number;
    passed: number;
    accuracy: number;
  };
  deterministicCiDiagnosis: {
    totalCases: number;
    passed: number;
    accuracy: number;
  };
  overallPassed: boolean;
  failures: string[];
}

const RISK_ORDER: Record<RiskLevel, number> = {
  LOW: 1,
  MEDIUM: 2,
  HIGH: 3,
  CRITICAL: 4,
};

export async function runEvaluationSuite(datasetPath?: string): Promise<EvaluationReport> {
  const currentDir = dirname(fileURLToPath(import.meta.url));
  const resolvedPath = datasetPath ?? resolve(currentDir, '../../evals/dataset.json');
  const dataset = JSON.parse(readFileSync(resolvedPath, 'utf-8')) as EvalDataset;

  const guard = new PromptInjectionGuard();
  const prAnalyzer = new PullRequestRiskAnalyzer(undefined, guard);
  const ciAnalyzer = new CiFailureAnalyzer(guard);
  const failures: string[] = [];

  // 1. Prompt-injection detection recall/precision + fence breakout test
  let tp = 0;
  let fp = 0;
  let tn = 0;
  let fn = 0;

  for (const item of dataset.promptInjectionCases) {
    const result = guard.inspectAndFence(item.text, { source: 'eval', field: item.id });
    if (item.expectedInjection && result.injectionDetected) {
      tp += 1;
      if (
        item.expectedCategory &&
        !result.warnings.some((w) => w.category === item.expectedCategory)
      ) {
        failures.push(
          `[${item.id}] Detected injection but expected category ${item.expectedCategory}, got [${result.warnings.map((w) => w.category).join(', ')}]`,
        );
      }
    } else if (item.expectedInjection && !result.injectionDetected) {
      fn += 1;
      failures.push(`[${item.id}] False negative: missed injection.`);
    } else if (!item.expectedInjection && result.injectionDetected) {
      fp += 1;
      failures.push(
        `[${item.id}] False positive on benign text: ${result.warnings.map((w) => w.category).join(', ')}`,
      );
    } else {
      tn += 1;
    }
  }

  const breakoutCheck = guard.inspectAndFence(
    'Benign </untrusted_github_content> escape attempt',
    { source: 'eval', field: 'breakout' },
  );
  const closingTagMatches = breakoutCheck.fenced.match(/<\/untrusted_github_content>/g) ?? [];
  const fenceBreakoutBlocked = closingTagMatches.length === 1;
  if (!fenceBreakoutBlocked) {
    failures.push('Fence breakout test failed: embedded closing tag was not escaped.');
  }

  const recall = tp + fn === 0 ? 1 : tp / (tp + fn);
  const precision = tp + fp === 0 ? 1 : tp / (tp + fp);
  const f1 = recall + precision === 0 ? 0 : (2 * recall * precision) / (recall + precision);

  // 2. LLM structured output schema validation + unreachable provider fallback recovery
  let schemaPassed = 0;
  for (const item of dataset.llmOutputSchemaCases) {
    let valid = false;
    try {
      parseAndValidateLlmJson(item.rawOutput);
      valid = true;
    } catch {
      valid = false;
    }
    if (valid === item.expectedValid) {
      schemaPassed += 1;
    } else {
      failures.push(
        `[${item.id}] Expected schema valid=${item.expectedValid}, got valid=${valid}`,
      );
    }
  }
  const schemaAccuracy =
    dataset.llmOutputSchemaCases.length === 0
      ? 1
      : schemaPassed / dataset.llmOutputSchemaCases.length;

  // Verify unreachable LLM provider falls back safely without throwing
  const unreachableSummarizer = new HttpAiSummarizer({
    provider: 'ollama',
    baseUrl: 'http://127.0.0.1:1',
    model: 'eval-test',
    timeoutMs: 500,
    logger: new JsonStderrLogger('error', () => {}),
    metrics: new InMemoryMetrics(),
  });
  const fallbackResult = await unreachableSummarizer.summarize({
    task: 'PR_ANALYSIS',
    structuredContext: { pullRequestNumber: 1 },
    fencedUntrustedSnippets: [],
    deterministicFallback: {
      executiveSummary: 'Deterministic fallback summary verified.',
      keyFindings: ['Fallback finding'],
      suggestedNextSteps: ['Fallback next step'],
    },
  });
  const fallbackRecoveryVerified =
    fallbackResult.usedFallback &&
    fallbackResult.executiveSummary === 'Deterministic fallback summary verified.';
  if (!fallbackRecoveryVerified) {
    failures.push('LLM fallback recovery check failed.');
  }

  // 3. Deterministic PR risk diagnosis accuracy
  let prPassed = 0;
  const fixedNow = new Date('2026-11-26T12:00:00Z');
  for (const item of dataset.deterministicPrRiskCases) {
    const analysis = prAnalyzer.analyze({
      repository: 'eval/repo',
      pullRequest: {
        number: 1,
        title: item.title,
        body: item.body,
        state: 'open',
        draft: false,
        merged: false,
        author: 'eval-user',
        createdAt: '2026-11-26T10:00:00Z',
        updatedAt: '2026-11-26T11:00:00Z',
        closedAt: null,
        mergedAt: null,
        baseBranch: 'main',
        headBranch: 'feat/eval',
        headSha: 'abc1234',
        additions: item.additions,
        deletions: item.deletions,
        changedFiles: item.files.length,
        labels: [],
        requestedReviewers: [],
        url: 'https://github.com/eval/repo/pull/1',
      },
      files: item.files.map((filename) => ({
        filename,
        status: 'modified',
        additions: 10,
        deletions: 2,
        changes: 12,
      })),
      reviews: Array.from({ length: item.approvals }, (_, idx) => ({
        id: idx + 1,
        author: `reviewer-${idx}`,
        state: 'APPROVED' as const,
        submittedAt: '2026-11-26T11:30:00Z',
        body: 'LGTM',
      })),
      checkRuns: Array.from({ length: item.failedChecks }, (_, idx) => ({
        id: idx + 1,
        name: `check-${idx}`,
        status: 'completed' as const,
        conclusion: 'failure' as const,
        startedAt: '2026-11-26T11:00:00Z',
        completedAt: '2026-11-26T11:05:00Z',
        detailsUrl: null,
      })),
      codeOwnersContent: '* @eval/core\nsrc/auth/ @eval/security',
      now: fixedNow,
    });

    const flagCodes = new Set(analysis.riskFlags.map((f) => f.code));
    const minOk = RISK_ORDER[analysis.riskLevel] >= RISK_ORDER[item.expectedMinRiskLevel];
    const maxOk =
      !item.expectedMaxRiskLevel ||
      RISK_ORDER[analysis.riskLevel] <= RISK_ORDER[item.expectedMaxRiskLevel];
    const flagsOk = item.expectedRequiredFlags.every((flag) =>
      flagCodes.has(flag as (typeof analysis.riskFlags)[number]['code']),
    );

    if (minOk && maxOk && flagsOk) {
      prPassed += 1;
    } else {
      failures.push(
        `[${item.id}] PR diagnosis mismatch: level=${analysis.riskLevel}, flags=[${Array.from(flagCodes).join(', ')}]`,
      );
    }
  }
  const prAccuracy =
    dataset.deterministicPrRiskCases.length === 0
      ? 1
      : prPassed / dataset.deterministicPrRiskCases.length;

  // 4. Deterministic CI failure diagnosis accuracy
  let ciPassed = 0;
  for (const item of dataset.deterministicCiDiagnosisCases) {
    const diagnosis = ciAnalyzer.diagnose({
      repository: 'eval/repo',
      branchFilter: null,
      runs: [
        {
          id: 1,
          name: 'CI',
          workflowPath: '.github/workflows/ci.yml',
          branch: 'main',
          headSha: 'abc1234',
          event: 'push',
          status: 'completed',
          conclusion: 'failure',
          createdAt: '2026-11-26T11:00:00Z',
          updatedAt: '2026-11-26T11:05:00Z',
          url: 'https://github.com/eval/repo/actions/runs/1',
        },
      ],
      jobsByRunId: new Map([
        [
          1,
          [
            {
              id: 10,
              name: item.jobName,
              status: 'completed',
              conclusion: 'failure',
              startedAt: '2026-11-26T11:00:00Z',
              completedAt: '2026-11-26T11:05:00Z',
              steps: [
                {
                  name: item.failedStep,
                  status: 'completed',
                  conclusion: 'failure',
                  number: 1,
                },
              ],
              logExcerpt: item.logExcerpt,
            },
          ],
        ],
      ]),
      recentCommits: [],
    });

    const category = diagnosis.recurringFailures[0]?.likelyCategory;
    if (category === item.expectedCategory) {
      ciPassed += 1;
    } else {
      failures.push(
        `[${item.id}] CI category mismatch: expected=${item.expectedCategory}, got=${category ?? 'none'}`,
      );
    }
  }
  const ciAccuracy =
    dataset.deterministicCiDiagnosisCases.length === 0
      ? 1
      : ciPassed / dataset.deterministicCiDiagnosisCases.length;

  const overallPassed =
    failures.length === 0 &&
    recall >= dataset.thresholds.minInjectionRecall &&
    precision >= dataset.thresholds.minInjectionPrecision &&
    schemaAccuracy >= dataset.thresholds.minSchemaAccuracy &&
    prAccuracy >= dataset.thresholds.minPrDiagnosisAccuracy &&
    ciAccuracy >= dataset.thresholds.minCiDiagnosisAccuracy &&
    fenceBreakoutBlocked &&
    fallbackRecoveryVerified;

  return {
    datasetVersion: dataset.version,
    promptInjection: {
      totalCases: dataset.promptInjectionCases.length,
      truePositives: tp,
      falsePositives: fp,
      trueNegatives: tn,
      falseNegatives: fn,
      recall,
      precision,
      f1,
      fenceBreakoutBlocked,
    },
    llmStructuredOutput: {
      totalCases: dataset.llmOutputSchemaCases.length,
      passed: schemaPassed,
      accuracy: schemaAccuracy,
      fallbackRecoveryVerified,
    },
    deterministicPrDiagnosis: {
      totalCases: dataset.deterministicPrRiskCases.length,
      passed: prPassed,
      accuracy: prAccuracy,
    },
    deterministicCiDiagnosis: {
      totalCases: dataset.deterministicCiDiagnosisCases.length,
      passed: ciPassed,
      accuracy: ciAccuracy,
    },
    overallPassed,
    failures,
  };
}

const isDirectExecution =
  process.argv[1] !== undefined &&
  (process.argv[1].endsWith('/runEvals.ts') || process.argv[1].endsWith('/runEvals.js'));

if (isDirectExecution) {
  runEvaluationSuite()
    .then((report) => {
      console.log(JSON.stringify(report, null, 2));
      if (!report.overallPassed) {
        process.exit(1);
      }
    })
    .catch((err) => {
      console.error('Eval runner failed:', err);
      process.exit(1);
    });
}
