import { LoggerPort } from '../../application/ports.js';
import { redactSecretsInObject, redactSecretsInString } from './secretRedactor.js';

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

const LEVEL_PRIORITY: Record<LogLevel, number> = {
  debug: 10,
  info: 20,
  warn: 30,
  error: 40,
};

export class JsonStderrLogger implements LoggerPort {
  private readonly minPriority: number;
  private readonly writer: (line: string) => void;

  constructor(level: LogLevel = 'info', writer?: (line: string) => void) {
    this.minPriority = LEVEL_PRIORITY[level];
    // Always write to stderr by default so MCP stdio JSON-RPC stdout is never corrupted
    this.writer = writer ?? ((line) => process.stderr.write(`${line}\n`));
  }

  public debug(message: string, meta?: Record<string, unknown>): void {
    this.emit('debug', message, meta);
  }

  public info(message: string, meta?: Record<string, unknown>): void {
    this.emit('info', message, meta);
  }

  public warn(message: string, meta?: Record<string, unknown>): void {
    this.emit('warn', message, meta);
  }

  public error(message: string, meta?: Record<string, unknown>): void {
    this.emit('error', message, meta);
  }

  private emit(level: LogLevel, message: string, meta?: Record<string, unknown>): void {
    if (LEVEL_PRIORITY[level] < this.minPriority) {
      return;
    }
    const payload = {
      timestamp: new Date().toISOString(),
      level,
      service: 'engineering-intelligence-mcp',
      message: redactSecretsInString(message),
      ...(meta ? (redactSecretsInObject(meta) as Record<string, unknown>) : {}),
    };
    this.writer(JSON.stringify(payload));
  }
}
