export type ErrorCode =
  | 'INVALID_ARGUMENT'
  | 'PERMISSION_DENIED'
  | 'CONFIRMATION_REQUIRED'
  | 'AMBIGUOUS_WRITE_OUTCOME'
  | 'CANCELLED'
  | 'AUTH_FAILED'
  | 'RATE_LIMITED'
  | 'NOT_FOUND'
  | 'UPSTREAM_UNAVAILABLE'
  | 'CIRCUIT_OPEN'
  | 'INTERNAL_ERROR';

export interface PublicErrorPayload {
  code: ErrorCode;
  message: string;
  retryable: boolean;
  details?: Record<string, unknown>;
}

export class AppError extends Error {
  public readonly code: ErrorCode;
  public readonly retryable: boolean;
  public readonly statusCode: number;
  public readonly details?: Record<string, unknown>;

  constructor(
    code: ErrorCode,
    message: string,
    options?: {
      retryable?: boolean;
      statusCode?: number;
      details?: Record<string, unknown>;
      cause?: unknown;
    },
  ) {
    super(message, options?.cause !== undefined ? { cause: options.cause } : undefined);
    this.name = 'AppError';
    this.code = code;
    this.retryable = options?.retryable ?? defaultRetryable(code);
    this.statusCode = options?.statusCode ?? defaultStatusCode(code);
    this.details = options?.details;
  }

  public toPublicPayload(): PublicErrorPayload {
    return {
      code: this.code,
      message: this.message,
      retryable: this.retryable,
      ...(this.details !== undefined ? { details: this.details } : {}),
    };
  }
}

function defaultRetryable(code: ErrorCode): boolean {
  switch (code) {
    case 'RATE_LIMITED':
    case 'UPSTREAM_UNAVAILABLE':
    case 'CIRCUIT_OPEN':
      return true;
    default:
      return false;
  }
}

function defaultStatusCode(code: ErrorCode): number {
  switch (code) {
    case 'INVALID_ARGUMENT':
      return 400;
    case 'AUTH_FAILED':
      return 401;
    case 'PERMISSION_DENIED':
      return 403;
    case 'NOT_FOUND':
      return 404;
    case 'CONFIRMATION_REQUIRED':
      return 409;
    case 'AMBIGUOUS_WRITE_OUTCOME':
      return 504;
    case 'CANCELLED':
      return 499;
    case 'RATE_LIMITED':
      return 429;
    case 'UPSTREAM_UNAVAILABLE':
      return 502;
    case 'CIRCUIT_OPEN':
      return 503;
    case 'INTERNAL_ERROR':
      return 500;
  }
}

export function isAppError(error: unknown): error is AppError {
  return error instanceof AppError;
}
