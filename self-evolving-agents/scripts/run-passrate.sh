#!/usr/bin/env sh
# Usage: scripts/run-passrate.sh <label> [rate] [duration]   (needs the compose stack up)
set -eu
cd "$(dirname "$0")/.."
LABEL=$1; RATE=${2:-70}; DURATION=${3:-120s}
mkdir -p load/results
docker compose --profile load run --rm -T \
  -v "$PWD/load/results:/results" -e LABEL="$LABEL" -e RATE="$RATE" -e DURATION="$DURATION" \
  --entrypoint k6 k6 run --quiet /scripts/pass-rate.js
