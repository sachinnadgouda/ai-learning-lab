import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

interface ParsedToolPayload {
  ok: boolean;
  correlationId?: string;
  toolName?: string;
  data?: Record<string, unknown>;
  error?: {
    code: string;
    message: string;
    details?: Record<string, unknown>;
  };
}

const currentDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(currentDir, '../..');
const compiledEntry = resolve(projectRoot, 'dist/index.js');

async function withMcpClient<T>(
  envOverrides: Record<string, string>,
  fn: (client: Client) => Promise<T>,
): Promise<T> {
  if (!existsSync(compiledEntry)) {
    throw new Error(
      `Compiled server not found at ${compiledEntry}. Run 'npm run build' before running the CLI.`,
    );
  }

  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [compiledEntry],
    env: {
      ...(process.env as Record<string, string>),
      GITHUB_MODE: process.env.GITHUB_MODE ?? 'demo',
      ALLOWED_REPOS: process.env.ALLOWED_REPOS ?? 'acme-eng/payments-platform',
      AUDIT_DB_PATH: process.env.AUDIT_DB_PATH ?? ':memory:',
      LOG_LEVEL: 'error',
      ...envOverrides,
    },
    stderr: 'pipe',
  });

  const client = new Client(
    { name: 'engineering-intelligence-cli', version: '1.0.0' },
    { capabilities: {} },
  );

  await client.connect(transport);
  try {
    return await fn(client);
  } finally {
    await client.close();
  }
}

function parseToolResult(result: Awaited<ReturnType<Client['callTool']>>): ParsedToolPayload {
  const content = Array.isArray(result.content) ? result.content : [];
  const firstText = content.find((c) => c.type === 'text') as { text: string } | undefined;
  if (!firstText) {
    throw new Error('MCP tool response contained no text payload.');
  }
  return JSON.parse(firstText.text) as ParsedToolPayload;
}

async function runFullDemo(): Promise<void> {
  const owner = 'acme-eng';
  const repo = 'payments-platform';
  const demoDbPath = resolve(projectRoot, 'data/cli-demo-audit.db');

  console.log('=== Engineering Intelligence MCP — End-to-End Stdio Demo ===\n');

  // Phase 1: Default read-only mode
  console.log('1) Connecting over stdio in default READ-ONLY mode...');
  await withMcpClient(
    {
      GITHUB_MODE: 'demo',
      PERMISSION_MODE: 'read-only',
      AUDIT_DB_PATH: demoDbPath,
      ACTOR_ID: 'cli-demo-user',
    },
    async (client) => {
      const toolsRes = await client.listTools();
      console.log(`   Discovered ${toolsRes.tools.length} MCP tools:`);
      for (const tool of toolsRes.tools) {
        const modeTag = tool.annotations?.readOnlyHint ? 'READ ' : 'WRITE';
        console.log(`     - [${modeTag}] ${tool.name}`);
      }

      const resourcesRes = await client.listResources();
      console.log(
        `   Discovered ${resourcesRes.resources.length} static resources: ${resourcesRes.resources.map((r) => r.uri).join(', ')}`,
      );

      console.log('\n2) Analyzing high-risk PR #142...');
      const pr142 = parseToolResult(
        await client.callTool({
          name: 'analyze_pull_request',
          arguments: { owner, repo, pullNumber: 142 },
        }),
      );
      console.log(
        `   Result: riskLevel=${pr142.data?.riskLevel}, riskScore=${pr142.data?.riskScore}, flags=${(pr142.data?.riskFlags as unknown[])?.length}, codeOwners=${(pr142.data?.codeOwners as unknown[])?.length}`,
      );

      console.log('\n3) Analyzing adversarial PR #145 (prompt-injection defense)...');
      const pr145 = parseToolResult(
        await client.callTool({
          name: 'analyze_pull_request',
          arguments: { owner, repo, pullNumber: 145 },
        }),
      );
      const warnings = (pr145.data?.injectionWarnings as Array<{ category: string }>) ?? [];
      console.log(
        `   Detected ${warnings.length} injection warning(s): ${Array.from(new Set(warnings.map((w) => w.category))).join(', ')}`,
      );

      console.log('\n4) Investigating CI failures...');
      const ci = parseToolResult(
        await client.callTool({
          name: 'investigate_ci_failures',
          arguments: { owner, repo, maxRuns: 10 },
        }),
      );
      console.log(
        `   Analyzed ${ci.data?.analyzedRuns} runs, ${ci.data?.failedRuns} failed (${ci.data?.failureRatePercent}%), recurring groups=${(ci.data?.recurringFailures as unknown[])?.length}`,
      );

      console.log('\n5) Computing repository health insights...');
      const insights = parseToolResult(
        await client.callTool({
          name: 'read_repo_insights',
          arguments: { owner, repo, windowDays: 30, stalePrDays: 7 },
        }),
      );
      console.log(
        `   Health status=${insights.data?.healthStatus} (${insights.data?.healthScore}/100)`,
      );

      console.log('\n6) Verifying write tool is BLOCKED in read-only mode...');
      const deniedWrite = parseToolResult(
        await client.callTool({
          name: 'write_add_pull_request_labels',
          arguments: { owner, repo, pullNumber: 142, labels: ['security-review-required'] },
        }),
      );
      console.log(
        `   Expected denial -> ok=${deniedWrite.ok}, code=${deniedWrite.error?.code}`,
      );
    },
  );

  // Phase 2: write-with-confirmation mode
  console.log('\n7) Reconnecting in WRITE-WITH-CONFIRMATION mode for 2-phase write flow...');
  await withMcpClient(
    {
      GITHUB_MODE: 'demo',
      PERMISSION_MODE: 'write-with-confirmation',
      AUDIT_DB_PATH: demoDbPath,
      ACTOR_ID: 'cli-demo-user',
      CONFIRMATION_SECRET: 'demo-cli-confirmation-secret-key-2026',
    },
    async (client) => {
      const writeArgs = {
        owner,
        repo,
        pullNumber: 142,
        labels: ['security-review-required'],
      };

      const challenge = parseToolResult(
        await client.callTool({
          name: 'write_add_pull_request_labels',
          arguments: writeArgs,
        }),
      );
      const token = String(challenge.error?.details?.confirmationToken ?? '');
      console.log(
        `   Phase 1 challenge -> code=${challenge.error?.code}, preview="${challenge.error?.details?.actionPreview}"`,
      );

      const confirmed = parseToolResult(
        await client.callTool({
          name: 'write_add_pull_request_labels',
          arguments: { ...writeArgs, confirmationToken: token },
        }),
      );
      if (!confirmed.ok) {
        throw new Error(`Expected Phase 2 write confirmation to succeed, got: ${JSON.stringify(confirmed.error)}`);
      }
      console.log(
        `   Phase 2 confirmed -> ok=${confirmed.ok}, appliedLabels=${JSON.stringify(confirmed.data?.appliedLabels)}`,
      );

      const replay = parseToolResult(
        await client.callTool({
          name: 'write_add_pull_request_labels',
          arguments: { ...writeArgs, confirmationToken: token },
        }),
      );
      if (replay.ok || replay.error?.code !== 'PERMISSION_DENIED') {
        throw new Error(`Expected replay to be denied, got: ${JSON.stringify(replay)}`);
      }
      console.log(
        `   Replay attempt    -> ok=${replay.ok}, code=${replay.error?.code}`,
      );

      console.log('\n8) Reading MCP resource engineering://audit/recent...');
      const auditResource = await client.readResource({ uri: 'engineering://audit/recent' });
      const textEntry = auditResource.contents[0] as { text?: string } | undefined;
      const auditJson = JSON.parse(textEntry?.text ?? '{}') as {
        totalRecords?: number;
        records?: Array<{ toolName: string; outcome: string }>;
      };
      console.log(`   SQLite audit trail persisted ${auditJson.totalRecords} total record(s).`);
      for (const rec of (auditJson.records ?? []).slice(0, 5)) {
        console.log(`     - ${rec.toolName} -> ${rec.outcome}`);
      }
    },
  );

  console.log('\n=== Demo completed successfully ===');
}

async function runCli(): Promise<void> {
  const [, , command = 'demo', ...rest] = process.argv;

  if (command === 'demo') {
    await runFullDemo();
    return;
  }

  if (command === 'tools') {
    await withMcpClient({}, async (client) => {
      const tools = await client.listTools();
      console.log(JSON.stringify(tools.tools, null, 2));
    });
    return;
  }

  if (command === 'analyze-pr') {
    const [owner = 'acme-eng', repo = 'payments-platform', prNumRaw = '142'] = rest;
    const pullNumber = Number(prNumRaw);
    await withMcpClient({}, async (client) => {
      const res = parseToolResult(
        await client.callTool({
          name: 'analyze_pull_request',
          arguments: { owner, repo, pullNumber },
        }),
      );
      console.log(JSON.stringify(res, null, 2));
    });
    return;
  }

  console.error(`Unknown CLI command '${command}'. Supported: demo | tools | analyze-pr <owner> <repo> <prNumber>`);
  process.exit(1);
}

runCli().catch((err) => {
  console.error('CLI error:', err instanceof Error ? err.message : String(err));
  process.exit(1);
});
