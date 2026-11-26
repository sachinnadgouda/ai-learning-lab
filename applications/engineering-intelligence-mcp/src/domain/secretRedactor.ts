const SENSITIVE_KEY_REGEX =
  /(?:token|secret|password|passwd|authorization|api[-_]?key|credential|cookie|private[-_]?key)/i;

const SECRET_VALUE_PATTERNS: RegExp[] = [
  /ghp_[A-Za-z0-9]{20,}/g,
  /gho_[A-Za-z0-9]{20,}/g,
  /ghu_[A-Za-z0-9]{20,}/g,
  /ghs_[A-Za-z0-9]{20,}/g,
  /github_pat_[A-Za-z0-9_]{20,}/g,
  /sk-[A-Za-z0-9_-]{20,}/g,
  /Bearer\s+[A-Za-z0-9._~+/=-]{8,}/gi,
  /token\s+[A-Za-z0-9._~+/=-]{12,}/gi,
  /conf_v1\.[a-f0-9]{32}\.[a-f0-9]{64}/g,
];

export function redactSecretsInString(input: string): string {
  let output = input;
  for (const pattern of SECRET_VALUE_PATTERNS) {
    pattern.lastIndex = 0;
    output = output.replace(pattern, '[REDACTED]');
  }
  return output;
}

export function redactSecretsInObject(value: unknown, depth = 0): unknown {
  if (depth > 8) {
    return '[TRUNCATED_DEPTH]';
  }
  if (value === null || value === undefined) {
    return value;
  }
  if (typeof value === 'string') {
    return redactSecretsInString(value);
  }
  if (typeof value !== 'object') {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map((item) => redactSecretsInObject(item, depth + 1));
  }

  const record = value as Record<string, unknown>;
  const sanitized: Record<string, unknown> = {};
  for (const [key, val] of Object.entries(record)) {
    if (SENSITIVE_KEY_REGEX.test(key)) {
      if (key === 'confirmationToken' && typeof val === 'string' && val.startsWith('conf_v1.')) {
        // Keep token structure redacted so logs/audit show presence without leaking HMAC
        sanitized[key] = '[REDACTED_CONFIRMATION_TOKEN]';
      } else {
        sanitized[key] = '[REDACTED]';
      }
      continue;
    }
    sanitized[key] = redactSecretsInObject(val, depth + 1);
  }
  return sanitized;
}
