import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { describe, expect, it } from 'vitest';

const currentDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(currentDir, '../..');
const compiledEntry = resolve(projectRoot, 'dist/index.js');

describe('Stdio MCP Server E2E', () => {
  it('connects a real MCP SDK Client over stdio to the server and executes tools & resources', async () => {
    const useCompiled = existsSync(compiledEntry);
    const transport = new StdioClientTransport({
      command: useCompiled ? process.execPath : resolve(projectRoot, 'node_modules/.bin/tsx'),
      args: [useCompiled ? compiledEntry : resolve(projectRoot, 'src/index.ts')],
      env: {
        ...(process.env as Record<string, string>),
        GITHUB_MODE: 'demo',
        PERMISSION_MODE: 'read-only',
        ALLOWED_REPOS: 'acme-eng/payments-platform',
        AUDIT_DB_PATH: ':memory:',
        LOG_LEVEL: 'error',
      },
      stderr: 'pipe',
    });

    const client = new Client({ name: 'e2e-stdio-test', version: '1.0.0' }, { capabilities: {} });
    await client.connect(transport);
    try {
      const tools = await client.listTools();
      expect(tools.tools.length).toBe(9);

      const prResult = await client.callTool({
        name: 'analyze_pull_request',
        arguments: { owner: 'acme-eng', repo: 'payments-platform', pullNumber: 142 },
      });
      const text = (prResult.content as Array<{ type: string; text: string }>)[0]!.text;
      const parsed = JSON.parse(text) as { ok: boolean; data: { riskLevel: string } };
      expect(parsed.ok).toBe(true);
      expect(parsed.data.riskLevel).toBe('CRITICAL');

      const audit = await client.readResource({ uri: 'engineering://audit/recent' });
      const auditParsed = JSON.parse((audit.contents[0] as { text: string }).text) as {
        totalRecords: number;
      };
      expect(auditParsed.totalRecords).toBe(1);
    } finally {
      await client.close();
    }
  });
});
