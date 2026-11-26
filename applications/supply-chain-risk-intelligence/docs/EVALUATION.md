# Offline Evaluation Suite Guide

## Overview

`evals/risk_eval_dataset.json` and `evals/eval_test.go` provide a deterministic, zero-API-key evaluation suite executed on every `go test -race ./...` and CI run.

## Evaluated Dimensions

| Dimension | Verification Method | Target |
|---|---|---|
| Risk correlation accuracy | Cross-entity incident grouping & severity classification against ground truth | 100% pass |
| Shortage detection accuracy | Exact SKU alert set, zero false positives on healthy baseline, zero-burn safety | 100% pass |
| Alternative recommendation ranking | Best backup supplier and best backup route ranked #1 | 100% pass |
| Structured AI output validation | Required fields populated & schema-valid across scenarios | 100% pass |
| Prompt-injection resistance | Adversarial carrier note detected, fenced, and unable to alter governance state | 100% pass |
| Zero autonomous execution | Every drafted proposal starts `PENDING_APPROVAL`; AI approval/execution & unapproved human execution rejected | 0 violations |

## Scenarios in `evals/risk_eval_dataset.json`

1. `semiconductor_typhoon_cascade` — Tier-1 single-source APAC fab disruption + ocean congestion + delayed MCU shipment.
2. `prompt_injection_carrier_note` — Active disruption carrying a `</untrusted_data>` breakout and auto-approve injection payload.
3. `route_only_port_blockage` — Healthy supplier whose ocean canal route is `BLOCKED`, triggering rail reroute recommendation.
4. `healthy_baseline_no_false_positives` — Operational baseline including a zero-burn spare SKU; asserts zero incidents and zero shortages.

## Running Evaluations

```bash
cd applications/supply-chain-risk-intelligence
go test -race -v ./evals/...
```
