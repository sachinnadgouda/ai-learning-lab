import { MetricsPort } from '../../application/ports.js';
import { AuditRecord, ToolPermissionClass } from '../../domain/entities.js';

export class InMemoryMetrics implements MetricsPort {
  private readonly toolCounters = new Map<string, number>();
  private readonly toolDurationSumMs = new Map<string, number>();
  private readonly upstreamCounters = new Map<string, number>();
  private injectionWarningsTotal = 0;
  private githubRateLimitRemaining: number | null = null;
  private readonly startedAt = new Date().toISOString();

  public recordToolExecution(params: {
    toolName: string;
    permissionClass: ToolPermissionClass;
    outcome: AuditRecord['outcome'];
    durationMs: number;
    injectionWarningsCount: number;
  }): void {
    const key = `${params.toolName}|${params.permissionClass}|${params.outcome}`;
    this.toolCounters.set(key, (this.toolCounters.get(key) ?? 0) + 1);
    this.toolDurationSumMs.set(
      params.toolName,
      (this.toolDurationSumMs.get(params.toolName) ?? 0) + params.durationMs,
    );
    this.injectionWarningsTotal += params.injectionWarningsCount;
  }

  public recordUpstreamRequest(params: {
    target: 'github' | 'ai';
    status: string;
    durationMs: number;
  }): void {
    const key = `${params.target}|${params.status}`;
    this.upstreamCounters.set(key, (this.upstreamCounters.get(key) ?? 0) + 1);
  }

  public recordRateLimit(remaining: number | null): void {
    this.githubRateLimitRemaining = remaining;
  }

  public renderPrometheus(): string {
    const lines: string[] = [
      '# HELP mcp_tool_executions_total Total MCP tool invocations by tool, permission class, and outcome',
      '# TYPE mcp_tool_executions_total counter',
    ];

    for (const [key, count] of this.toolCounters.entries()) {
      const [tool, permClass, outcome] = key.split('|');
      lines.push(
        `mcp_tool_executions_total{tool="${escapeLabel(tool!)}",permission="${escapeLabel(permClass!)}",outcome="${escapeLabel(outcome!)}"} ${count}`,
      );
    }

    lines.push(
      '# HELP mcp_tool_duration_ms_sum Cumulative execution duration in milliseconds per tool',
      '# TYPE mcp_tool_duration_ms_sum counter',
    );
    for (const [tool, sumMs] of this.toolDurationSumMs.entries()) {
      lines.push(`mcp_tool_duration_ms_sum{tool="${escapeLabel(tool)}"} ${sumMs}`);
    }

    lines.push(
      '# HELP mcp_prompt_injection_warnings_total Total prompt injection warnings detected in GitHub content',
      '# TYPE mcp_prompt_injection_warnings_total counter',
      `mcp_prompt_injection_warnings_total ${this.injectionWarningsTotal}`,
    );

    lines.push(
      '# HELP mcp_upstream_requests_total Total upstream HTTP requests by target and status',
      '# TYPE mcp_upstream_requests_total counter',
    );
    for (const [key, count] of this.upstreamCounters.entries()) {
      const [target, status] = key.split('|');
      lines.push(
        `mcp_upstream_requests_total{target="${escapeLabel(target!)}",status="${escapeLabel(status!)}"} ${count}`,
      );
    }

    if (this.githubRateLimitRemaining !== null) {
      lines.push(
        '# HELP github_rate_limit_remaining Last observed GitHub REST API rate-limit remaining count',
        '# TYPE github_rate_limit_remaining gauge',
        `github_rate_limit_remaining ${this.githubRateLimitRemaining}`,
      );
    }

    return `${lines.join('\n')}\n`;
  }

  public getSnapshot(): Record<string, unknown> {
    return {
      startedAt: this.startedAt,
      toolExecutions: Object.fromEntries(this.toolCounters.entries()),
      toolDurationSumMs: Object.fromEntries(this.toolDurationSumMs.entries()),
      upstreamRequests: Object.fromEntries(this.upstreamCounters.entries()),
      injectionWarningsTotal: this.injectionWarningsTotal,
      githubRateLimitRemaining: this.githubRateLimitRemaining,
    };
  }
}

function escapeLabel(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}
