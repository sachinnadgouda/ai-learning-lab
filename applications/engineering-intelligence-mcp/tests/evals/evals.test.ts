import { createServer, Server } from 'node:http';
import { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { runEvaluationSuite } from '../../src/evals/runEvals.js';
import { HttpAiSummarizer } from '../../src/infrastructure/ai/HttpAiSummarizer.js';
import { JsonStderrLogger } from '../../src/infrastructure/observability/logger.js';
import { InMemoryMetrics } from '../../src/infrastructure/observability/metrics.js';

describe('AI & Prompt-Injection Evaluation Suite', () => {
  const servers: Server[] = [];

  afterEach(async () => {
    while (servers.length > 0) {
      const srv = servers.pop()!;
      await new Promise<void>((resolve) => srv.close(() => resolve()));
    }
  });

  it('meets all offline evaluation thresholds for prompt injection, schema validation, and deterministic diagnosis', async () => {
    const report = await runEvaluationSuite();
    expect(report.overallPassed).toBe(true);
    expect(report.failures).toEqual([]);
    expect(report.promptInjection.recall).toBe(1);
    expect(report.promptInjection.precision).toBe(1);
    expect(report.promptInjection.fenceBreakoutBlocked).toBe(true);
    expect(report.llmStructuredOutput.accuracy).toBe(1);
    expect(report.llmStructuredOutput.fallbackRecoveryVerified).toBe(true);
    expect(report.deterministicPrDiagnosis.accuracy).toBe(1);
    expect(report.deterministicCiDiagnosis.accuracy).toBe(1);
  });

  it('validates structured JSON from Ollama endpoint and falls back safely on malformed LLM JSON', async () => {
    let callCount = 0;
    const server = createServer((_req, res) => {
      callCount += 1;
      res.writeHead(200, { 'content-type': 'application/json' });
      if (callCount === 1) {
        res.end(
          JSON.stringify({
            message: {
              content: JSON.stringify({
                executiveSummary: 'PR #142 touches sensitive auth and IAM paths without tests.',
                keyFindings: ['Sensitive path touched', 'Unit tests failed'],
                suggestedNextSteps: ['Add negative JWKS tests', 'Request security review'],
              }),
            },
            prompt_eval_count: 210,
            eval_count: 64,
          }),
        );
      } else {
        res.end(
          JSON.stringify({
            message: { content: 'NOT VALID JSON OUTPUT FROM MODEL' },
          }),
        );
      }
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
    const { port } = server.address() as AddressInfo;

    const summarizer = new HttpAiSummarizer({
      provider: 'ollama',
      baseUrl: `http://127.0.0.1:${port}`,
      model: 'llama3.2:1b',
      timeoutMs: 2000,
      logger: new JsonStderrLogger('error', () => {}),
      metrics: new InMemoryMetrics(),
    });

    const input = {
      task: 'PR_ANALYSIS' as const,
      structuredContext: { pullRequestNumber: 142 },
      fencedUntrustedSnippets: ['<untrusted_github_content>snippet</untrusted_github_content>'],
      deterministicFallback: {
        executiveSummary: 'Deterministic fallback summary.',
        keyFindings: ['Fallback finding'],
        suggestedNextSteps: ['Fallback action'],
      },
    };

    const validRes = await summarizer.summarize(input);
    expect(validRes.usedFallback).toBe(false);
    expect(validRes.promptTokens).toBe(210);
    expect(validRes.completionTokens).toBe(64);
    expect(validRes.executiveSummary).toContain('PR #142');

    const malformedRes = await summarizer.summarize(input);
    expect(malformedRes.usedFallback).toBe(true);
    expect(malformedRes.executiveSummary).toBe('Deterministic fallback summary.');
  });
});
