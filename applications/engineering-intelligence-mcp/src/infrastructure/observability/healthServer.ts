import { createHash, timingSafeEqual } from 'node:crypto';
import { createServer, IncomingMessage, Server, ServerResponse } from 'node:http';
import { LoggerPort, MetricsPort } from '../../application/ports.js';

export interface HealthStatus {
  status: 'ok' | 'degraded';
  service: string;
  version: string;
  timestamp: string;
  uptimeSeconds: number;
  github: { healthy: boolean; mode: 'demo' | 'live'; detail: string; circuitState?: string };
  auditStore: { healthy: boolean; totalRecords: number };
  permissionMode: string;
  aiProvider: string;
}

export interface HealthServerSecurityOptions {
  metricsEnabled?: boolean;
  metricsAuthToken?: string;
}

export class HealthHttpServer {
  private server: Server | null = null;
  private readonly metricsEnabled: boolean;
  private readonly metricsAuthToken: string;

  constructor(
    private readonly host: string,
    private readonly port: number,
    private readonly getHealth: () => HealthStatus,
    private readonly metrics: MetricsPort,
    private readonly logger: LoggerPort,
    options?: HealthServerSecurityOptions,
  ) {
    this.metricsEnabled = options?.metricsEnabled ?? true;
    this.metricsAuthToken = (options?.metricsAuthToken ?? '').trim();
  }

  public async start(): Promise<void> {
    if (this.port <= 0) {
      return;
    }

    this.server = createServer((req: IncomingMessage, res: ServerResponse) => {
      const rawUrl = req.url ?? '/';
      const pathname = rawUrl.split('?')[0] ?? '/';

      if (pathname === '/health' || pathname === '/ready') {
        const health = this.getHealth();
        const statusCode = health.status === 'ok' ? 200 : 503;
        res.writeHead(statusCode, {
          'content-type': 'application/json; charset=utf-8',
          'cache-control': 'no-store',
        });
        res.end(JSON.stringify(health));
        return;
      }

      if (pathname === '/metrics') {
        if (!this.metricsEnabled) {
          res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: 'Metrics endpoint is disabled by configuration.' }));
          return;
        }

        if (this.metricsAuthToken.length > 0 && !this.isAuthorizedMetricsRequest(req)) {
          res.writeHead(401, {
            'content-type': 'application/json; charset=utf-8',
            'www-authenticate': 'Bearer realm="metrics"',
            'cache-control': 'no-store',
          });
          res.end(
            JSON.stringify({
              error: 'Unauthorized',
              message:
                'Valid Authorization: Bearer <token> or X-Metrics-Token header is required for /metrics.',
            }),
          );
          return;
        }

        res.writeHead(200, {
          'content-type': 'text/plain; version=0.0.4; charset=utf-8',
          'cache-control': 'no-store',
        });
        res.end(this.metrics.renderPrometheus());
        return;
      }

      res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
      res.end(
        JSON.stringify({
          error: 'Not Found',
          endpoints: this.metricsEnabled ? ['/health', '/ready', '/metrics'] : ['/health', '/ready'],
        }),
      );
    });

    await new Promise<void>((resolve, reject) => {
      this.server!.once('error', reject);
      this.server!.listen(this.port, this.host, () => {
        this.logger.info('health.server.started', {
          host: this.host,
          port: this.port,
          metricsEnabled: this.metricsEnabled,
          metricsAuthProtected: this.metricsAuthToken.length > 0,
        });
        resolve();
      });
    });
  }

  public async stop(): Promise<void> {
    if (!this.server) {
      return;
    }
    const srv = this.server;
    this.server = null;
    await new Promise<void>((resolve) => {
      srv.close(() => resolve());
    });
  }

  private isAuthorizedMetricsRequest(req: IncomingMessage): boolean {
    const authHeader = req.headers.authorization;
    if (typeof authHeader === 'string' && authHeader.toLowerCase().startsWith('bearer ')) {
      const candidate = authHeader.slice(7).trim();
      if (constantTimeStringEqual(candidate, this.metricsAuthToken)) {
        return true;
      }
    }

    const tokenHeader = req.headers['x-metrics-token'];
    if (typeof tokenHeader === 'string' && constantTimeStringEqual(tokenHeader.trim(), this.metricsAuthToken)) {
      return true;
    }

    return false;
  }
}

function constantTimeStringEqual(a: string, b: string): boolean {
  const hashA = createHash('sha256').update(a).digest();
  const hashB = createHash('sha256').update(b).digest();
  return timingSafeEqual(hashA, hashB);
}
