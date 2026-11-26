# Offline AI & Prompt-Injection Evaluation

## 1. Goals

Every AI-adjacent control in `engineering-intelligence-mcp` is verified deterministically against a versioned offline dataset (`evals/dataset.json`) both via `npm run eval` and inside Vitest (`tests/evals/evals.test.ts`).

## 2. Evaluation Suites & Thresholds

| Suite | Cases | Metric | CI Threshold |
|---|---|---|---|
| Prompt-Injection Detection | 16 (8 adversarial + 8 benign) + fence breakout test | Recall, Precision, F1, Breakout blocked | Recall `= 1.0`, Precision `= 1.0`, Breakout blocked `= true` |
| LLM Structured Output Validation | 5 JSON / malformed cases + unreachable provider test | Schema accuracy & safe fallback recovery | Accuracy `= 1.0`, Fallback verified `= true` |
| Deterministic PR Risk Diagnosis | 3 canonical PR archetypes (high-risk security, clean tested, adversarial) | Risk tier & mandatory risk flags accuracy | Accuracy `= 1.0` |
| Deterministic CI Failure Diagnosis | 3 failure logs (test, infra timeout, security CVE) | Failure category accuracy | Accuracy `= 1.0` |

## 3. Running Locally

```bash
npm run eval
```

Exits with code `0` and prints a structured JSON report when all thresholds pass; exits with code `1` and lists exact case failures on regression.
