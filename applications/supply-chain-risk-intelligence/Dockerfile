# syntax=docker/dockerfile:1

FROM golang:1.24.2-alpine3.21 AS builder

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/server ./cmd/server \
 && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/simulator ./cmd/simulator

FROM alpine:3.21.3 AS runtime

RUN apk add --no-cache ca-certificates tzdata \
 && addgroup -S appgroup \
 && adduser -S appuser -G appgroup \
 && mkdir -p /data \
 && chown -R appuser:appgroup /data

WORKDIR /app

COPY --from=builder /out/server /app/server
COPY --from=builder /out/simulator /app/simulator

USER appuser

ENV HOST=0.0.0.0 \
    PORT=8080 \
    DATABASE_URL=/data/supply_chain.db \
    EVENT_BROKER=memory \
    AI_PROVIDER=none

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["/app/server"]
