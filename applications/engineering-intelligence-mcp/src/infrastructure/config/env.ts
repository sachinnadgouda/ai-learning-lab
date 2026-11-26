import { randomBytes } from 'node:crypto';
import { z } from 'zod';
import { DeploymentMode, PermissionMode } from '../../domain/entities.js';
import { parseAllowedRepoEntry } from '../../domain/permissionPolicy.js';
import { LogLevel } from '../observability/logger.js';

const BooleanFlagSchema = z
  .enum(['true', 'false'])
  .optional()
  .default('false')
  .transform((v) => v === 'true');

const RawEnvSchema = z.object({
  DEPLOYMENT_MODE: z.enum(['local-stdio', 'shared-server']).default('local-stdio'),

  GITHUB_MODE: z.enum(['demo', 'live']).default('demo'),
  GITHUB_TOKEN: z.string().optional().default(''),
  GITHUB_API_BASE_URL: z.string().url().default('https://api.github.com'),
  GITHUB_TIMEOUT_MS: z.coerce.number().int().min(100).max(120_000).default(10_000),
  GITHUB_MAX_RETRIES: z.coerce.number().int().min(0).max(8).default(3),
  GITHUB_MAX_PAGES: z.coerce.number().int().min(1).max(20).default(5),

  PERMISSION_MODE: z.enum(['read-only', 'write-with-confirmation']).default('read-only'),
  ALLOWED_REPOS: z.string().optional().default(''),
  ALLOW_UNRESTRICTED_DEMO_REPOS: BooleanFlagSchema,
  ACTOR_ID: z.string().trim().min(1).max(100).default('local-developer'),

  CONFIRMATION_SECRET: z.string().optional().default(''),
  CONFIRMATION_TTL_SECONDS: z.coerce.number().int().min(30).max(3600).default(300),

  AUDIT_DB_PATH: z.string().trim().min(1).default('./data/audit.db'),

  AI_PROVIDER: z.enum(['none', 'ollama', 'openai-compatible']).default('none'),
  AI_BASE_URL: z.string().url().default('http://127.0.0.1:11434'),
  AI_MODEL: z.string().trim().min(1).default('llama3.2:1b'),
  AI_API_KEY: z.string().optional().default(''),
  AI_TIMEOUT_MS: z.coerce.number().int().min(500).max(120_000).default(15_000),

  HEALTH_PORT: z.coerce.number().int().min(0).max(65535).default(0),
  HEALTH_HOST: z.string().trim().min(1).default('127.0.0.1'),
  METRICS_ENABLED: z
    .enum(['true', 'false'])
    .optional()
    .default('true')
    .transform((v) => v === 'true'),
  METRICS_AUTH_TOKEN: z.string().optional().default(''),

  LOG_LEVEL: z.enum(['debug', 'info', 'warn', 'error']).default('info'),
});

export interface AppConfig {
  deploymentMode: DeploymentMode;
  githubMode: 'demo' | 'live';
  githubToken: string;
  githubApiBaseUrl: string;
  githubTimeoutMs: number;
  githubMaxRetries: number;
  githubMaxPages: number;
  permissionMode: PermissionMode;
  allowedRepos: string[];
  allowUnrestrictedDemoRepos: boolean;
  actorId: string;
  confirmationSecret: string;
  confirmationSecretEphemeral: boolean;
  confirmationTtlSeconds: number;
  auditDbPath: string;
  aiProvider: 'none' | 'ollama' | 'openai-compatible';
  aiBaseUrl: string;
  aiModel: string;
  aiApiKey: string;
  aiTimeoutMs: number;
  healthPort: number;
  healthHost: string;
  metricsEnabled: boolean;
  metricsAuthToken: string;
  logLevel: LogLevel;
}

export function isLoopbackHost(host: string): boolean {
  const normalized = host.trim().toLowerCase();
  return (
    normalized === '127.0.0.1' ||
    normalized === 'localhost' ||
    normalized === '::1' ||
    normalized === '[::1]'
  );
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = RawEnvSchema.safeParse(env);
  if (!parsed.success) {
    const formatted = parsed.error.issues
      .map((i) => `${i.path.join('.')}: ${i.message}`)
      .join('; ');
    throw new Error(`Invalid startup configuration: ${formatted}`);
  }

  const data = parsed.data;
  if (data.GITHUB_MODE === 'live' && data.GITHUB_TOKEN.trim().length < 10) {
    throw new Error(
      'Invalid startup configuration: GITHUB_TOKEN is required (min 10 chars) when GITHUB_MODE=live. Set GITHUB_MODE=demo to run with offline fixtures.',
    );
  }

  // Parse and strictly validate ALLOWED_REPOS entries (empty tokens between commas fail fast).
  const rawAllowlist = data.ALLOWED_REPOS.trim();
  const allowedRepos: string[] = [];
  if (rawAllowlist.length > 0) {
    const rawParts = rawAllowlist.split(',');
    for (const part of rawParts) {
      const validated = parseAllowedRepoEntry(part);
      allowedRepos.push(validated.normalized);
    }
  }

  // Enforce explicit isolation of unrestricted local demo mode vs restricted deployments.
  if (data.ALLOW_UNRESTRICTED_DEMO_REPOS) {
    if (data.GITHUB_MODE !== 'demo') {
      throw new Error(
        'Invalid startup configuration: ALLOW_UNRESTRICTED_DEMO_REPOS=true is forbidden when GITHUB_MODE=live. Configure an explicit ALLOWED_REPOS allowlist.',
      );
    }
    if (data.DEPLOYMENT_MODE !== 'local-stdio') {
      throw new Error(
        'Invalid startup configuration: ALLOW_UNRESTRICTED_DEMO_REPOS=true is forbidden in shared-server deployment mode.',
      );
    }
    if (allowedRepos.length > 0) {
      throw new Error(
        'Invalid startup configuration: ALLOW_UNRESTRICTED_DEMO_REPOS=true cannot be combined with explicit ALLOWED_REPOS entries.',
      );
    }
  }

  if (data.GITHUB_MODE === 'live' && allowedRepos.length === 0) {
    throw new Error(
      "Invalid startup configuration: ALLOWED_REPOS must specify at least one 'owner/repo' (or 'owner/*') entry when GITHUB_MODE=live (default-deny policy).",
    );
  }

  // Enforce confirmation secret strength and persistence in shared-server deployments.
  if (data.DEPLOYMENT_MODE === 'shared-server') {
    if (data.CONFIRMATION_SECRET.length < 32) {
      throw new Error(
        'Invalid startup configuration: CONFIRMATION_SECRET (min 32 chars) is required in DEPLOYMENT_MODE=shared-server; ephemeral secrets are not permitted.',
      );
    }
  } else if (data.CONFIRMATION_SECRET.length > 0 && data.CONFIRMATION_SECRET.length < 16) {
    throw new Error(
      'Invalid startup configuration: CONFIRMATION_SECRET must be at least 16 characters when explicitly set.',
    );
  }

  const metricsAuthToken = data.METRICS_AUTH_TOKEN.trim();
  if (metricsAuthToken.length > 0 && metricsAuthToken.length < 16) {
    throw new Error(
      'Invalid startup configuration: METRICS_AUTH_TOKEN must be at least 16 characters when explicitly set.',
    );
  }

  if (
    data.DEPLOYMENT_MODE === 'shared-server' &&
    data.HEALTH_PORT > 0 &&
    !isLoopbackHost(data.HEALTH_HOST) &&
    data.METRICS_ENABLED &&
    metricsAuthToken.length === 0
  ) {
    throw new Error(
      'Invalid startup configuration: exposing /metrics on a non-loopback interface in DEPLOYMENT_MODE=shared-server requires METRICS_AUTH_TOKEN (min 16 chars) or METRICS_ENABLED=false.',
    );
  }

  const confirmationSecretEphemeral = data.CONFIRMATION_SECRET.length === 0;
  const confirmationSecret = confirmationSecretEphemeral
    ? randomBytes(32).toString('hex')
    : data.CONFIRMATION_SECRET;

  return {
    deploymentMode: data.DEPLOYMENT_MODE,
    githubMode: data.GITHUB_MODE,
    githubToken: data.GITHUB_TOKEN.trim(),
    githubApiBaseUrl: data.GITHUB_API_BASE_URL.replace(/\/+$/, ''),
    githubTimeoutMs: data.GITHUB_TIMEOUT_MS,
    githubMaxRetries: data.GITHUB_MAX_RETRIES,
    githubMaxPages: data.GITHUB_MAX_PAGES,
    permissionMode: data.PERMISSION_MODE,
    allowedRepos,
    allowUnrestrictedDemoRepos: data.ALLOW_UNRESTRICTED_DEMO_REPOS,
    actorId: data.ACTOR_ID,
    confirmationSecret,
    confirmationSecretEphemeral,
    confirmationTtlSeconds: data.CONFIRMATION_TTL_SECONDS,
    auditDbPath: data.AUDIT_DB_PATH,
    aiProvider: data.AI_PROVIDER,
    aiBaseUrl: data.AI_BASE_URL.replace(/\/+$/, ''),
    aiModel: data.AI_MODEL,
    aiApiKey: data.AI_API_KEY.trim(),
    aiTimeoutMs: data.AI_TIMEOUT_MS,
    healthPort: data.HEALTH_PORT,
    healthHost: data.HEALTH_HOST,
    metricsEnabled: data.METRICS_ENABLED,
    metricsAuthToken,
    logLevel: data.LOG_LEVEL,
  };
}
