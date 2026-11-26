# syntax=docker/dockerfile:1.7

FROM node:22.15-alpine AS builder
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY tsconfig.json ./
COPY src ./src
COPY evals ./evals
RUN npm run build

FROM node:22.15-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production \
    GITHUB_MODE=demo \
    PERMISSION_MODE=read-only \
    AUDIT_DB_PATH=/app/data/audit.db \
    HEALTH_HOST=0.0.0.0 \
    HEALTH_PORT=8081 \
    LOG_LEVEL=info

COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && \
    mkdir -p /app/data && \
    chown -R node:node /app

COPY --from=builder --chown=node:node /app/dist ./dist
COPY --from=builder --chown=node:node /app/evals ./evals

USER node

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:' + (process.env.HEALTH_PORT || 8081) + '/health').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"

ENTRYPOINT ["node", "dist/index.js"]
