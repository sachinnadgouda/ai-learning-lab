> **Historical document (v0.1).** The `/api/*` endpoints it mentions were replaced by `/api/v1` in v0.2 (see [API.md](API.md) and [REFACTORING_CHANGELOG.md](REFACTORING_CHANGELOG.md)). Behaviour descriptions may be outdated.

# Bidirectional optimizer update

## What changed

- Ollama can recommend increasing or decreasing admission and queue capacity, within fixed safety bounds.
- Ollama can explicitly respond with `NO_CHANGE` or `INSUFFICIENT_DATA`.
- Candidate storage rejects semantic no-ops, stale versions and out-of-range parameters.
- Automatic cycles record no-candidate decisions instead of trying to replay a null candidate.
- Replay evaluation uses the same arrival trace for baseline and candidate; it compares admitted requests, rejections, modeled backend overload, estimated p95 latency, and estimated queue waits.
- Manual approval still uses a synthetic workload and requires a passing result.

## Important limitations

The replay model is not a measurement of HTTP p95 latency, actual backend errors, or real customer queue fairness. Its estimated metrics are intentionally labeled as such. The manual approval endpoint uses a synthetic trace rather than the exact recent traffic trace used by the autonomous coordinator. **Do not deploy this as a production traffic-control system.** Further work should persist replay evidence bound to the candidate and enforce approval against that evidence, and should benchmark both policies against isolated real services.

## Test locally

```sh
docker compose up --build -d
docker compose exec ollama ollama pull qwen2.5:3b
docker compose --profile load run --rm k6
```

With `DEMO_ADMIN_TOKEN` loaded from `.env`, enable AI and call `POST /api/admin/ai/propose`, then `GET /api/admin/evaluate`. Inspect the candidate separately from the active policy and only approve when the evaluation reports `valid: true`.

Run Gradle tests via a locally installed Gradle 8.x and Java 21, or the Docker build stage if configured.
