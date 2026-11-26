import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import {
  AuditRecord,
  ExecutionIdentity,
  ToolPermissionClass,
  WriteReconciliationRecord,
} from '../domain/entities.js';
import { AppError, isAppError } from '../domain/errors.js';
import { PermissionPolicy } from '../domain/permissionPolicy.js';
import { ConfirmationService } from '../domain/confirmationService.js';
import {
  AuditStorePort,
  LoggerPort,
  MetricsPort,
  WriteReconciliationStorePort,
} from './ports.js';
import { redactSecretsInObject, redactSecretsInString } from '../domain/secretRedactor.js';
import { formatOperationMarker } from './usecases/createIssueComment.js';

export interface WriteReconciliationSeed {
  toolName: WriteReconciliationRecord['toolName'];
  targetNumber: number | null;
  expectedLabels?: string[];
}

export interface ToolExecutionDescriptor<TInput extends Record<string, unknown>, TOutput> {
  toolName: string;
  permissionClass: ToolPermissionClass;
  schema: z.ZodType<TInput, z.ZodTypeDef, unknown>;
  timeoutMs?: number;
  extractRepo?: (input: NoInfer<TInput>) => { owner: string; repo: string } | undefined;
  buildActionPreview?: (input: NoInfer<TInput>) => string;
  buildReconciliationSeed?: (input: NoInfer<TInput>) => WriteReconciliationSeed;
  extractInjectionCount?: (output: NoInfer<TOutput>) => number;
  summarizeOutcome?: (output: NoInfer<TOutput>) => string;
  execute: (
    input: NoInfer<TInput>,
    context: {
      correlationId: string;
      actor: string;
      sessionId: string;
      signal: AbortSignal;
    },
  ) => Promise<TOutput>;
}

export interface ToolExecutionOptions {
  callerIdentity?: ExecutionIdentity;
  signal?: AbortSignal;
}

export interface ToolExecutionResponse<TOutput> {
  ok: boolean;
  correlationId: string;
  toolName: string;
  permissionClass: ToolPermissionClass;
  data?: TOutput;
  error?: {
    code: string;
    message: string;
    retryable: boolean;
    details?: Record<string, unknown>;
  };
}

export class ToolExecutionOrchestrator {
  private readonly permissionPolicy: PermissionPolicy;
  private readonly confirmationService: ConfirmationService;
  private readonly auditStore: AuditStorePort;
  private readonly reconciliationStore?: WriteReconciliationStorePort;
  private readonly logger: LoggerPort;
  private readonly metrics: MetricsPort;
  private readonly defaultTimeoutMs: number;
  private readonly localSessionId: string;

  constructor(deps: {
    permissionPolicy: PermissionPolicy;
    confirmationService: ConfirmationService;
    auditStore: AuditStorePort;
    reconciliationStore?: WriteReconciliationStorePort;
    logger: LoggerPort;
    metrics: MetricsPort;
    defaultTimeoutMs?: number;
    localSessionId?: string;
  }) {
    this.permissionPolicy = deps.permissionPolicy;
    this.confirmationService = deps.confirmationService;
    this.auditStore = deps.auditStore;
    this.reconciliationStore = deps.reconciliationStore;
    this.logger = deps.logger;
    this.metrics = deps.metrics;
    this.defaultTimeoutMs = deps.defaultTimeoutMs ?? 30_000;
    this.localSessionId = deps.localSessionId ?? `local-stdio-${randomUUID().slice(0, 12)}`;
  }

  public async execute<TInput extends Record<string, unknown>, TOutput>(
    descriptor: ToolExecutionDescriptor<TInput, TOutput>,
    rawArgs: unknown,
    options?: ToolExecutionOptions,
  ): Promise<ToolExecutionResponse<TOutput>> {
    const correlationId = randomUUID();
    const startMs = Date.now();
    const deploymentMode = this.confirmationService.getDeploymentMode();

    const effectiveIdentity: ExecutionIdentity =
      options?.callerIdentity ??
      (deploymentMode === 'shared-server'
        ? {
            principalId: '',
            sessionId: '',
            authenticated: false,
            authMethod: 'local-process-env',
          }
        : {
            principalId: this.permissionPolicy.actor,
            sessionId: this.localSessionId,
            authenticated: false,
            authMethod: 'local-process-env',
          });

    const actor = effectiveIdentity.principalId || 'unauthenticated';
    let repository: string | null = null;
    let validatedInput: TInput | undefined;
    let outcome: AuditRecord['outcome'] = 'SUCCESS';
    let errorCode: string | null = null;
    let injectionWarningsCount = 0;
    let summary = 'Completed successfully';

    const redactedArgs = redactSecretsInObject(rawArgs ?? {});

    try {
      const parsedResult = descriptor.schema.safeParse(rawArgs);
      if (!parsedResult.success) {
        const issues = parsedResult.error.issues.map(
          (issue) => `${issue.path.join('.') || 'root'}: ${issue.message}`,
        );
        throw new AppError('INVALID_ARGUMENT', `Invalid tool arguments: ${issues.join('; ')}`, {
          details: { validationErrors: issues },
        });
      }

      const validated = parsedResult.data;
      validatedInput = validated;
      const repoRef = descriptor.extractRepo?.(validated);
      if (repoRef) {
        repository = `${repoRef.owner}/${repoRef.repo}`;
      }

      this.permissionPolicy.assertAllowed({
        toolName: descriptor.toolName,
        permissionClass: descriptor.permissionClass,
        owner: repoRef?.owner,
        repo: repoRef?.repo,
      });

      if (descriptor.permissionClass === 'WRITE') {
        const confirmationToken =
          typeof validated.confirmationToken === 'string'
            ? validated.confirmationToken
            : undefined;
        const preview =
          descriptor.buildActionPreview?.(validated) ??
          `Execute write tool '${descriptor.toolName}' on ${repository ?? 'unknown'}`;

        this.confirmationService.requireOrConsume({
          toolName: descriptor.toolName,
          actor: effectiveIdentity.principalId,
          sessionId: effectiveIdentity.sessionId,
          authenticated: effectiveIdentity.authenticated,
          repository: repository ?? 'global',
          args: validated,
          actionPreview: preview,
          confirmationToken,
        });
      }

      const timeoutMs = descriptor.timeoutMs ?? this.defaultTimeoutMs;
      const output = await runWithCancellableTimeout(
        (signal) =>
          descriptor.execute(validated, {
            correlationId,
            actor,
            sessionId: effectiveIdentity.sessionId,
            signal,
          }),
        {
          timeoutMs,
          permissionClass: descriptor.permissionClass,
          callerSignal: options?.signal,
        },
      );

      injectionWarningsCount = descriptor.extractInjectionCount?.(output) ?? 0;
      summary = descriptor.summarizeOutcome?.(output) ?? `Executed ${descriptor.toolName}`;

      this.logger.info('tool.execution.succeeded', {
        correlationId,
        actor,
        sessionId: effectiveIdentity.sessionId,
        toolName: descriptor.toolName,
        permissionClass: descriptor.permissionClass,
        repository,
        durationMs: Date.now() - startMs,
        injectionWarningsCount,
      });

      return {
        ok: true,
        correlationId,
        toolName: descriptor.toolName,
        permissionClass: descriptor.permissionClass,
        data: output,
      };
    } catch (err) {
      let appError = normalizeError(err);

      // Record ambiguous write reconciliation metadata and return clear guidance to MCP client.
      if (descriptor.permissionClass === 'WRITE' && appError.code === 'AMBIGUOUS_WRITE_OUTCOME') {
        let reconciliationRecorded = false;
        const operationMarker = formatOperationMarker(correlationId);

        if (this.reconciliationStore && validatedInput && descriptor.buildReconciliationSeed) {
          const seed = descriptor.buildReconciliationSeed(validatedInput);
          try {
            this.reconciliationStore.saveWriteReconciliation({
              correlationId,
              toolName: seed.toolName,
              repository: repository ?? 'unknown',
              targetNumber: seed.targetNumber,
              operationMarker,
              expectedLabels: seed.expectedLabels ?? [],
              actor,
              status: 'AMBIGUOUS',
              createdAt: new Date().toISOString(),
              resolvedAt: null,
              resolutionDetail: null,
            });
            reconciliationRecorded = true;
          } catch (recErr) {
            this.logger.error('reconciliation.save.failed', {
              correlationId,
              toolName: descriptor.toolName,
              error: redactSecretsInString(recErr instanceof Error ? recErr.message : String(recErr)),
            });
          }
        }

        appError = new AppError('AMBIGUOUS_WRITE_OUTCOME', appError.message, {
          retryable: false,
          statusCode: 504,
          cause: appError,
          details: {
            ...(appError.details ?? {}),
            correlationId,
            operationMarker,
            doNotBlindlyRetry: true,
            reconciliationRecorded,
            reconcileTool: 'read_reconcile_write_outcome',
            reconcileArgs: { correlationId },
            explanation:
              'The write request was dispatched before timeout or connection loss, so GitHub may have already applied the mutation. Do NOT blindly retry; call read_reconcile_write_outcome with this correlationId to inspect GitHub state first.',
          },
        });
      }

      errorCode = appError.code;

      if (appError.code === 'PERMISSION_DENIED' || appError.code === 'AUTH_FAILED') {
        outcome = 'DENIED';
      } else if (appError.code === 'CONFIRMATION_REQUIRED') {
        outcome = 'CONFIRMATION_REQUIRED';
      } else if (appError.code === 'AMBIGUOUS_WRITE_OUTCOME') {
        outcome = 'AMBIGUOUS';
      } else {
        outcome = 'ERROR';
      }

      summary = redactSecretsInString(appError.message);

      this.logger.warn('tool.execution.failed', {
        correlationId,
        actor,
        sessionId: effectiveIdentity.sessionId,
        toolName: descriptor.toolName,
        permissionClass: descriptor.permissionClass,
        repository,
        outcome,
        errorCode,
        message: summary,
        durationMs: Date.now() - startMs,
      });

      const safePayload = appError.toPublicPayload();
      const responseDetails =
        appError.code === 'CONFIRMATION_REQUIRED'
          ? safePayload.details
          : safePayload.details
            ? (redactSecretsInObject(safePayload.details) as Record<string, unknown>)
            : undefined;

      return {
        ok: false,
        correlationId,
        toolName: descriptor.toolName,
        permissionClass: descriptor.permissionClass,
        error: {
          ...safePayload,
          message: redactSecretsInString(safePayload.message),
          ...(responseDetails !== undefined ? { details: responseDetails } : {}),
        },
      };
    } finally {
      const durationMs = Date.now() - startMs;
      this.metrics.recordToolExecution({
        toolName: descriptor.toolName,
        permissionClass: descriptor.permissionClass,
        outcome,
        durationMs,
        injectionWarningsCount,
      });

      try {
        this.auditStore.append({
          correlationId,
          timestamp: new Date().toISOString(),
          actor,
          toolName: descriptor.toolName,
          permissionClass: descriptor.permissionClass,
          repository,
          outcome,
          errorCode,
          durationMs,
          injectionWarningsCount,
          redactedArgsJson: JSON.stringify(redactedArgs),
          summary,
        });
      } catch (auditErr) {
        this.logger.error('audit.append.failed', {
          correlationId,
          toolName: descriptor.toolName,
          error: redactSecretsInString(auditErr instanceof Error ? auditErr.message : String(auditErr)),
        });
      }
    }
  }
}

function normalizeError(err: unknown): AppError {
  if (isAppError(err)) {
    return err;
  }
  return new AppError(
    'INTERNAL_ERROR',
    'An unexpected internal error occurred while executing the tool.',
    { cause: err },
  );
}

async function runWithCancellableTimeout<T>(
  fn: (signal: AbortSignal) => Promise<T>,
  options: {
    timeoutMs: number;
    permissionClass: ToolPermissionClass;
    callerSignal?: AbortSignal;
  },
): Promise<T> {
  if (options.callerSignal?.aborted) {
    throw new AppError(
      'CANCELLED',
      'Tool execution was cancelled before starting; no upstream request was dispatched.',
      {
        retryable: false,
        details: { phase: 'before_send', sideEffectPossible: false },
      },
    );
  }

  const timeoutController = new AbortController();
  const effectiveSignal = options.callerSignal
    ? AbortSignal.any([options.callerSignal, timeoutController.signal])
    : timeoutController.signal;

  let timedOut = false;
  let graceTimer: NodeJS.Timeout | undefined;
  let timer: NodeJS.Timeout | undefined;

  const executionPromise = fn(effectiveSignal);

  const timeoutFallbackPromise = new Promise<never>((_, reject) => {
    const buildTimeoutError = () => {
      if (options.permissionClass === 'WRITE') {
        return new AppError(
          'AMBIGUOUS_WRITE_OUTCOME',
          `Write tool execution timed out after ${options.timeoutMs}ms while in progress. Upstream mutation state is ambiguous; do NOT blindly retry.`,
          {
            retryable: false,
            details: {
              phase: 'in_flight_or_response_lost',
              sideEffectPossible: true,
              doNotBlindlyRetry: true,
            },
          },
        );
      }
      return new AppError(
        'UPSTREAM_UNAVAILABLE',
        `Tool execution timed out after ${options.timeoutMs}ms.`,
        { retryable: true },
      );
    };

    timer = setTimeout(() => {
      timedOut = true;
      timeoutController.abort();
      // Give the underlying AbortSignal-aware call a short window to reject with its exact phase
      // (e.g., CANCELLED before_send vs AMBIGUOUS_WRITE_OUTCOME in_flight), then enforce hard cutoff.
      graceTimer = setTimeout(() => {
        reject(buildTimeoutError());
      }, 150);
      graceTimer.unref?.();
    }, options.timeoutMs);
    timer.unref?.();
  });

  try {
    return await Promise.race([executionPromise, timeoutFallbackPromise]);
  } catch (err) {
    // If the underlying execution rejected after timeout with a generic non-AppError, classify cleanly.
    if (timedOut && !isAppError(err)) {
      if (options.permissionClass === 'WRITE') {
        throw new AppError(
          'AMBIGUOUS_WRITE_OUTCOME',
          `Write tool execution timed out after ${options.timeoutMs}ms while in progress.`,
          {
            retryable: false,
            cause: err,
            details: {
              phase: 'in_flight_or_response_lost',
              sideEffectPossible: true,
              doNotBlindlyRetry: true,
            },
          },
        );
      }
      throw new AppError(
        'UPSTREAM_UNAVAILABLE',
        `Tool execution timed out after ${options.timeoutMs}ms.`,
        { retryable: true, cause: err },
      );
    }
    throw err;
  } finally {
    if (timer) clearTimeout(timer);
    if (graceTimer) clearTimeout(graceTimer);
    // Prevent unhandled rejection if executionPromise rejects after fallback won the race.
    executionPromise.catch(() => {});
  }
}
