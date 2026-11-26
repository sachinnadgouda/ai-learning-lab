import { PermissionMode, ToolPermissionClass } from './entities.js';
import { AppError } from './errors.js';

const REPO_SEGMENT_REGEX = /^[a-z0-9](?:[a-z0-9._-]{0,98}[a-z0-9])?$|^[a-z0-9]$/;

export interface ParsedAllowedRepoRule {
  normalized: string;
  owner: string;
  repo: string; // exact repo name or '*' for org-scoped wildcard
  isOrgWildcard: boolean;
}

/**
 * Validates and normalizes a single ALLOWED_REPOS entry.
 * Supports exact 'owner/repo' entries and org-scoped 'owner/*' wildcards.
 * Explicitly rejects global wildcards (bare '*', '* / *', '* / repo'), partial globs ('acme/pay*'),
 * path traversal segments, and malformed entries.
 */
export function parseAllowedRepoEntry(raw: string): ParsedAllowedRepoRule {
  const trimmed = raw.trim().toLowerCase();
  if (trimmed.length === 0) {
    throw new AppError(
      'INVALID_ARGUMENT',
      'ALLOWED_REPOS contains an empty repository entry.',
    );
  }

  if (trimmed === '*' || trimmed === '*/*') {
    throw new AppError(
      'INVALID_ARGUMENT',
      `Global wildcard '${raw.trim()}' is forbidden in ALLOWED_REPOS. Specify explicit 'owner/repo' entries or an org-scoped 'owner/*' pattern.`,
    );
  }

  const parts = trimmed.split('/');
  if (parts.length !== 2) {
    throw new AppError(
      'INVALID_ARGUMENT',
      `Malformed ALLOWED_REPOS entry '${raw.trim()}': expected 'owner/repo' or 'owner/*'.`,
    );
  }

  const owner = parts[0] ?? '';
  const repo = parts[1] ?? '';

  if (owner === '*' || owner.includes('*')) {
    throw new AppError(
      'INVALID_ARGUMENT',
      `Owner wildcard '${raw.trim()}' is forbidden in ALLOWED_REPOS. Owner must be an explicit GitHub user or organization name.`,
    );
  }

  if (owner.includes('..') || !REPO_SEGMENT_REGEX.test(owner)) {
    throw new AppError(
      'INVALID_ARGUMENT',
      `Malformed owner segment in ALLOWED_REPOS entry '${raw.trim()}'.`,
    );
  }

  if (repo === '*') {
    return {
      normalized: `${owner}/*`,
      owner,
      repo: '*',
      isOrgWildcard: true,
    };
  }

  if (repo.includes('*')) {
    throw new AppError(
      'INVALID_ARGUMENT',
      `Partial wildcard '${raw.trim()}' is not supported in ALLOWED_REPOS. Use exact 'owner/repo' or full org-scoped 'owner/*'.`,
    );
  }

  if (repo.includes('..') || !REPO_SEGMENT_REGEX.test(repo)) {
    throw new AppError(
      'INVALID_ARGUMENT',
      `Malformed repository segment in ALLOWED_REPOS entry '${raw.trim()}'.`,
    );
  }

  return {
    normalized: `${owner}/${repo}`,
    owner,
    repo,
    isOrgWildcard: false,
  };
}

export interface PermissionPolicyConfig {
  mode: PermissionMode;
  allowedRepos: string[];
  actor: string;
  /**
   * Explicit opt-in for unrestricted local demo mode ONLY.
   * Default is false (secure default-deny when allowedRepos is empty).
   */
  allowUnrestrictedDemoRepos?: boolean;
}

export interface ToolAccessRequest {
  toolName: string;
  permissionClass: ToolPermissionClass;
  owner?: string;
  repo?: string;
}

export class PermissionPolicy {
  private readonly mode: PermissionMode;
  private readonly exactRepos: Set<string>;
  private readonly wildcardOwners: Set<string>;
  private readonly allowUnrestrictedDemoRepos: boolean;
  public readonly actor: string;

  constructor(config: PermissionPolicyConfig) {
    this.mode = config.mode;
    this.actor = config.actor;
    this.allowUnrestrictedDemoRepos = config.allowUnrestrictedDemoRepos === true;
    this.exactRepos = new Set();
    this.wildcardOwners = new Set();

    const nonEmptyEntries = config.allowedRepos
      .map((entry) => entry.trim())
      .filter((entry) => entry.length > 0);

    if (this.allowUnrestrictedDemoRepos && nonEmptyEntries.length > 0) {
      throw new AppError(
        'INVALID_ARGUMENT',
        'Invalid repository policy: allowUnrestrictedDemoRepos cannot be combined with explicit ALLOWED_REPOS entries.',
      );
    }

    for (const rawEntry of nonEmptyEntries) {
      const parsed = parseAllowedRepoEntry(rawEntry);
      if (parsed.isOrgWildcard) {
        this.wildcardOwners.add(parsed.owner);
      } else {
        this.exactRepos.add(parsed.normalized);
      }
    }
  }

  public getMode(): PermissionMode {
    return this.mode;
  }

  public isUnrestrictedDemoMode(): boolean {
    return this.allowUnrestrictedDemoRepos;
  }

  public getAllowedRepos(): string[] {
    const entries = [
      ...Array.from(this.exactRepos.values()),
      ...Array.from(this.wildcardOwners.values()).map((owner) => `${owner}/*`),
    ];
    return entries.sort();
  }

  public isRepoAllowed(owner: string, repo: string): boolean {
    const normalizedOwner = owner.trim().toLowerCase();
    const normalizedRepo = repo.trim().toLowerCase();
    if (!normalizedOwner || !normalizedRepo) {
      return false;
    }

    if (this.allowUnrestrictedDemoRepos) {
      return true;
    }

    // Secure default-deny: an empty allowlist denies every repository.
    if (this.exactRepos.size === 0 && this.wildcardOwners.size === 0) {
      return false;
    }

    if (this.wildcardOwners.has(normalizedOwner)) {
      return true;
    }

    return this.exactRepos.has(`${normalizedOwner}/${normalizedRepo}`);
  }

  public assertAllowed(request: ToolAccessRequest): void {
    if (request.owner !== undefined && request.repo !== undefined) {
      if (!this.isRepoAllowed(request.owner, request.repo)) {
        const allowlistCount = this.exactRepos.size + this.wildcardOwners.size;
        const reason =
          allowlistCount === 0
            ? `Repository '${request.owner}/${request.repo}' is denied by default because ALLOWED_REPOS is empty. Configure ALLOWED_REPOS with explicit 'owner/repo' (or 'owner/*') entries.`
            : `Repository '${request.owner}/${request.repo}' is not in the server's ALLOWED_REPOS allowlist.`;
        throw new AppError('PERMISSION_DENIED', reason, {
          details: {
            toolName: request.toolName,
            repository: `${request.owner}/${request.repo}`,
            allowedReposCount: allowlistCount,
            defaultDeny: allowlistCount === 0,
          },
        });
      }
    }

    if (request.permissionClass === 'WRITE' && this.mode !== 'write-with-confirmation') {
      throw new AppError(
        'PERMISSION_DENIED',
        `Write tool '${request.toolName}' is blocked because server PERMISSION_MODE is '${this.mode}'. Set PERMISSION_MODE=write-with-confirmation to enable confirmed writes.`,
        {
          details: {
            toolName: request.toolName,
            permissionClass: request.permissionClass,
            currentMode: this.mode,
            requiredMode: 'write-with-confirmation',
          },
        },
      );
    }
  }
}
