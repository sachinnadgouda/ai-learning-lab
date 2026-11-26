import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { ConfirmationRecord, DeploymentMode } from './entities.js';
import { AppError } from './errors.js';

export interface ConfirmationStore {
  saveConfirmation(record: ConfirmationRecord): void;
  findConfirmationByNonce(nonce: string): ConfirmationRecord | null;
  consumeConfirmation(nonce: string, consumedAt: string): boolean;
  expireOldConfirmations(nowIso: string): number;
}

export interface ConfirmationChallenge {
  confirmationToken: string;
  expiresAt: string;
  toolName: string;
  repository: string;
  actionPreview: string;
  argsDigest: string;
  deploymentMode: DeploymentMode;
  boundPrincipal: string;
  boundSessionId: string;
  trustModel: 'local-single-user-guardrail' | 'shared-authenticated-principal-session';
  independentHumanAuth: boolean;
  trustNotice: string;
}

export interface ConfirmationVerifyInput {
  toolName: string;
  actor: string;
  /**
   * Session identifier binding the two-phase token to an active authenticated session.
   * Required in shared-server mode; defaults to a local process session ID in local-stdio mode.
   */
  sessionId?: string;
  /**
   * True only when actor & sessionId came from an authenticated transport/session layer
   * rather than a static local environment variable (ACTOR_ID).
   */
  authenticated?: boolean;
  repository: string;
  args: Record<string, unknown>;
  actionPreview: string;
  confirmationToken?: string;
}

const TOKEN_PREFIX = 'conf_v2';
const DEFAULT_LOCAL_SESSION_ID = 'local-stdio-session';

export class ConfirmationService {
  private readonly secret: string;
  private readonly ttlSeconds: number;
  private readonly store: ConfirmationStore;
  private readonly deploymentMode: DeploymentMode;
  private readonly clock: () => Date;

  constructor(options: {
    secret: string;
    ttlSeconds: number;
    store: ConfirmationStore;
    deploymentMode?: DeploymentMode;
    clock?: () => Date;
  }) {
    const minSecretLen = options.deploymentMode === 'shared-server' ? 32 : 16;
    if (options.secret.length < minSecretLen) {
      throw new AppError(
        'INVALID_ARGUMENT',
        `Confirmation HMAC secret must be at least ${minSecretLen} characters long in '${options.deploymentMode ?? 'local-stdio'}' mode.`,
      );
    }
    this.secret = options.secret;
    this.ttlSeconds = options.ttlSeconds;
    this.store = options.store;
    this.deploymentMode = options.deploymentMode ?? 'local-stdio';
    this.clock = options.clock ?? (() => new Date());
  }

  public getDeploymentMode(): DeploymentMode {
    return this.deploymentMode;
  }

  public computeArgsDigest(args: Record<string, unknown>): string {
    const filtered: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(args)) {
      if (key === 'confirmationToken') {
        continue;
      }
      filtered[key] = value;
    }
    const canonical = canonicalJsonStringify(filtered);
    return createHash('sha256').update(canonical).digest('hex');
  }

  public requireOrConsume(input: ConfirmationVerifyInput): { confirmed: true; nonce: string } {
    const actor = input.actor.trim();
    const sessionId = (input.sessionId ?? '').trim();

    if (this.deploymentMode === 'shared-server') {
      if (input.authenticated !== true || actor.length === 0 || sessionId.length === 0) {
        throw new AppError(
          'AUTH_FAILED',
          'Shared-server write confirmation requires an authenticated principal and active session ID. A static ACTOR_ID or bearer token possession alone is not accepted as proof of human identity.',
          {
            details: {
              deploymentMode: this.deploymentMode,
              authenticated: input.authenticated === true,
              hasPrincipal: actor.length > 0,
              hasSessionId: sessionId.length > 0,
            },
          },
        );
      }
    } else if (actor.length === 0) {
      throw new AppError('INVALID_ARGUMENT', 'Confirmation actor must be non-empty.');
    }

    const effectiveSessionId = sessionId.length > 0 ? sessionId : DEFAULT_LOCAL_SESSION_ID;

    const now = this.clock();
    const nowIso = now.toISOString();
    this.store.expireOldConfirmations(nowIso);

    const argsDigest = this.computeArgsDigest(input.args);

    if (!input.confirmationToken || input.confirmationToken.trim().length === 0) {
      const challenge = this.issueChallenge({
        toolName: input.toolName,
        actor,
        sessionId: effectiveSessionId,
        repository: input.repository,
        argsDigest,
        actionPreview: input.actionPreview,
        now,
      });

      const promptMessage =
        this.deploymentMode === 'shared-server'
          ? `Confirmation required for authenticated principal '${actor}' (session '${effectiveSessionId}') before executing '${input.toolName}'. Re-invoke within the same authenticated principal and session with identical arguments plus confirmationToken.`
          : `Two-phase confirmation required before executing '${input.toolName}' (local-stdio guardrail; bound to local process actor '${actor}'). Review the action preview and re-invoke with identical arguments plus confirmationToken.`;

      throw new AppError('CONFIRMATION_REQUIRED', promptMessage, {
        details: {
          ...challenge,
          instructions:
            'Re-call the exact same tool with identical arguments and include confirmationToken before expiresAt.',
        },
      });
    }

    return this.verifyAndConsume({
      token: input.confirmationToken.trim(),
      toolName: input.toolName,
      actor,
      sessionId: effectiveSessionId,
      repository: input.repository,
      argsDigest,
      now,
    });
  }

  private issueChallenge(params: {
    toolName: string;
    actor: string;
    sessionId: string;
    repository: string;
    argsDigest: string;
    actionPreview: string;
    now: Date;
  }): ConfirmationChallenge {
    const nonce = randomBytes(16).toString('hex');
    const createdAt = params.now.toISOString();
    const expiresAt = new Date(params.now.getTime() + this.ttlSeconds * 1000).toISOString();
    const signature = this.sign({
      nonce,
      toolName: params.toolName,
      actor: params.actor,
      sessionId: params.sessionId,
      deploymentMode: this.deploymentMode,
      repository: params.repository,
      argsDigest: params.argsDigest,
      expiresAt,
    });
    const token = `${TOKEN_PREFIX}.${nonce}.${signature}`;
    const tokenHash = sha256Hex(token);

    this.store.saveConfirmation({
      nonce,
      tokenHash,
      toolName: params.toolName,
      actor: params.actor,
      sessionId: params.sessionId,
      deploymentMode: this.deploymentMode,
      repository: params.repository,
      argsDigest: params.argsDigest,
      actionPreview: params.actionPreview,
      status: 'PENDING',
      createdAt,
      expiresAt,
      consumedAt: null,
    });

    const isShared = this.deploymentMode === 'shared-server';
    return {
      confirmationToken: token,
      expiresAt,
      toolName: params.toolName,
      repository: params.repository,
      actionPreview: params.actionPreview,
      argsDigest: params.argsDigest,
      deploymentMode: this.deploymentMode,
      boundPrincipal: params.actor,
      boundSessionId: params.sessionId,
      trustModel: isShared
        ? 'shared-authenticated-principal-session'
        : 'local-single-user-guardrail',
      independentHumanAuth: false,
      trustNotice: isShared
        ? 'Token is cryptographically bound to the authenticated principal and session; possession of the token alone is insufficient across principals or sessions.'
        : 'Local single-user stdio process: two-phase confirmation prevents accidental or prompt-injected writes but does NOT prove independent human authentication.',
    };
  }

  private verifyAndConsume(params: {
    token: string;
    toolName: string;
    actor: string;
    sessionId: string;
    repository: string;
    argsDigest: string;
    now: Date;
  }): { confirmed: true; nonce: string } {
    const parts = params.token.split('.');
    if (parts.length !== 3 || parts[0] !== TOKEN_PREFIX) {
      throw new AppError('PERMISSION_DENIED', 'Invalid confirmation token format.');
    }
    const nonce = parts[1] ?? '';
    const providedSig = parts[2] ?? '';
    if (!/^[a-f0-9]{32}$/.test(nonce) || !/^[a-f0-9]{64}$/.test(providedSig)) {
      throw new AppError('PERMISSION_DENIED', 'Malformed confirmation token.');
    }

    const stored = this.store.findConfirmationByNonce(nonce);
    if (!stored) {
      throw new AppError('PERMISSION_DENIED', 'Confirmation token is unknown or unrecognized.');
    }

    if (stored.status === 'CONSUMED') {
      throw new AppError(
        'PERMISSION_DENIED',
        'Confirmation token has already been used (replay rejected). Request a new confirmation token.',
      );
    }

    const nowMs = params.now.getTime();
    const expiresMs = Date.parse(stored.expiresAt);
    if (stored.status === 'EXPIRED' || Number.isNaN(expiresMs) || nowMs >= expiresMs) {
      throw new AppError(
        'PERMISSION_DENIED',
        'Confirmation token has expired. Request a new confirmation token.',
      );
    }

    const expectedSig = this.sign({
      nonce: stored.nonce,
      toolName: params.toolName,
      actor: params.actor,
      sessionId: params.sessionId,
      deploymentMode: this.deploymentMode,
      repository: params.repository,
      argsDigest: params.argsDigest,
      expiresAt: stored.expiresAt,
    });

    if (!constantTimeHexEqual(providedSig, expectedSig)) {
      throw new AppError(
        'PERMISSION_DENIED',
        'Confirmation token signature or bound context (principal, session, deployment mode, or arguments) does not match.',
      );
    }

    const providedHash = sha256Hex(params.token);
    if (!constantTimeHexEqual(providedHash, stored.tokenHash)) {
      throw new AppError('PERMISSION_DENIED', 'Confirmation token integrity check failed.');
    }

    if (
      stored.toolName !== params.toolName ||
      stored.actor !== params.actor ||
      stored.sessionId !== params.sessionId ||
      stored.deploymentMode !== this.deploymentMode ||
      stored.repository !== params.repository ||
      stored.argsDigest !== params.argsDigest
    ) {
      throw new AppError(
        'PERMISSION_DENIED',
        'Confirmation token does not match the requested tool, repository, principal, session, or arguments.',
      );
    }

    const consumed = this.store.consumeConfirmation(stored.nonce, params.now.toISOString());
    if (!consumed) {
      throw new AppError(
        'PERMISSION_DENIED',
        'Confirmation token could not be consumed (possible concurrent replay).',
      );
    }

    return { confirmed: true, nonce: stored.nonce };
  }

  private sign(payload: {
    nonce: string;
    toolName: string;
    actor: string;
    sessionId: string;
    deploymentMode: DeploymentMode;
    repository: string;
    argsDigest: string;
    expiresAt: string;
  }): string {
    const message = [
      payload.nonce,
      payload.toolName,
      payload.actor,
      payload.sessionId,
      payload.deploymentMode,
      payload.repository,
      payload.argsDigest,
      payload.expiresAt,
    ].join('|');
    return createHmac('sha256', this.secret).update(message).digest('hex');
  }
}

function sha256Hex(value: string): string {
  return createHash('sha256').update(value).digest('hex');
}

function constantTimeHexEqual(a: string, b: string): boolean {
  const bufA = Buffer.from(a, 'hex');
  const bufB = Buffer.from(b, 'hex');
  if (bufA.length !== bufB.length || bufA.length === 0) {
    return false;
  }
  return timingSafeEqual(bufA, bufB);
}

export function canonicalJsonStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalJsonStringify(item)).join(',')}]`;
  }
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj)
    .filter((k) => obj[k] !== undefined)
    .sort();
  const entries = keys.map((k) => `${JSON.stringify(k)}:${canonicalJsonStringify(obj[k])}`);
  return `{${entries.join(',')}}`;
}
