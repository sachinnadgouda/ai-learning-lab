#!/bin/sh
# API smoke test against a running backend (no Ollama needed): exercises the deterministic
# propose -> evaluate -> approve -> rollback path through /api/v1.
set -eu
BASE_URL="${BASE_URL:-http://localhost:8080}"
: "${DEMO_ADMIN_TOKEN:?Export DEMO_ADMIN_TOKEN before running}"
AUTH="X-Demo-Admin: $DEMO_ADMIN_TOKEN"
json() { curl -fsS -H "$AUTH" -H 'Content-Type: application/json' "$@"; printf '\n'; }

echo "== public health"; curl -fsS "$BASE_URL/api/v1/system/health"; printf '\n'
echo "== admin endpoints reject anonymous access"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/flags")
[ "$code" = "401" ] || { echo "expected 401, got $code"; exit 1; }
echo "== generate a little traffic"
for i in 1 2 3 4 5; do curl -s -o /dev/null "$BASE_URL/api/v1/shop"; done
echo "== clear any pending candidate (ignored if none)"
curl -s -o /dev/null -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{"reason":"smoke reset"}' "$BASE_URL/api/v1/policies/candidate/reject" || true
echo "== active policy"; json "$BASE_URL/api/v1/policies"
echo "== propose deterministic candidate"; json -X POST "$BASE_URL/api/v1/policies/candidates/deterministic"
echo "== evaluate"; json -X POST "$BASE_URL/api/v1/policies/candidate/evaluate"
echo "== dashboard"; json "$BASE_URL/api/v1/dashboard/summary"
echo "== reject candidate (leaves the active policy unchanged)"; json -X POST -d '{"reason":"smoke test"}' "$BASE_URL/api/v1/policies/candidate/reject"
echo "smoke OK"
