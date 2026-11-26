#!/bin/sh
# Docker Compose smoke test: builds and starts the stack, waits for health, checks every exposed service.
# Requires a populated .env (see .env.example). Does not require the Ollama model to be downloaded.
set -eu
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Create .env from .env.example first"; exit 1; }
# .env is Compose syntax, not shell syntax: read only the one value needed.
DEMO_ADMIN_TOKEN=$(sed -n 's/^DEMO_ADMIN_TOKEN=//p' .env | head -1)
export DEMO_ADMIN_TOKEN
docker compose up --build -d
trap 'echo "(stack left running; stop with: docker compose down)"' EXIT

wait_for() {  # name url [header]
  i=0
  until curl -fsS ${3:+-H "$3"} -o /dev/null "$2"; do
    i=$((i+1)); [ $i -gt 60 ] && { echo "FAIL: $1 not ready ($2)"; exit 1; }
    sleep 3
  done
  echo "OK: $1"
}
wait_for backend   http://localhost:8080/api/v1/system/health
wait_for frontend  http://localhost:4200/
wait_for proxy     http://localhost:4200/api/v1/system/health
wait_for prometheus "http://localhost:9090/-/ready"
wait_for grafana   http://localhost:3000/api/health
wait_for ollama    http://localhost:11434/api/tags
wait_for flags     http://localhost:4200/api/v1/flags "X-Demo-Admin: $DEMO_ADMIN_TOKEN"
curl -fsS http://localhost:8080/actuator/prometheus | grep -q '^demo_policy_active_version' && echo "OK: metrics exposed"
BASE_URL=http://localhost:8080 sh scripts/smoke.sh
echo "compose smoke OK"
