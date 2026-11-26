# Security & AI Governance

## Threat Model & Controls

| Threat | Mitigation Implemented |
|---|---|
| Prompt injection via untrusted carrier/supplier event notes | `domain.Sanitizer` scans for injection phrases, strips control bytes, neutralizes closing tags (`</untrusted_data>`), caps length, and wraps notes in `<untrusted_data source="...">...</untrusted_data>`. System prompts explicitly mark fenced content as data only. |
| Autonomous AI execution of supply-chain mutations | Enforced at domain layer (`ChangeProposal.Approve`, `Reject`, `MarkExecuted`): `ActorAISystem` always fails with `ErrAutonomousExecutionForbidden`; unapproved proposals always fail with `ErrApprovalRequired`. Drafted proposals always start in `PENDING_APPROVAL`. |
| Unauthenticated / unauthorized API access & identity spoofing | Server-side constant-time `X-API-Key` validation against a trusted credential-to-principal registry (`operator`, `approver`, `admin`, `ai_service`). Audit `ActorID` and `ActorType` are derived strictly from the authenticated credential; any client-supplied `X-Actor-ID` or `X-Actor-Type` header is rejected with `403 Forbidden` (`identity-override-forbidden`) and recorded as an `IDENTITY_OVERRIDE_REJECTED` audit event. Approval and execution endpoints strictly require human roles (`approver`/`admin`, and `operator`/`approver`/`admin` for execution of approved proposals). |
| Secret leakage in logs, DLQ, or LLM prompts | `Sanitizer.RedactSecrets` masks Bearer tokens, API keys (`sk-...`, `X-API-Key`), and `password=` patterns before logging, DLQ persistence, or prompt construction. |
| DoS via large payloads or event floods | 1 MiB `http.MaxBytesReader`, per-IP token-bucket rate limiter, bounded intake queue (`QUEUE_CAPACITY`) returning HTTP 429 instead of unbounded memory growth. |
| Malformed / adversarial LLM JSON output | `json.Decoder` with `DisallowUnknownFields()`, required field checks, enum validation against `ProposalType`, confidence range check `[0,1]`, plus deterministic fallback degradation. |
| Container privilege escalation | Multi-stage Dockerfile builds static Go binary (`CGO_ENABLED=0`) and runs under non-root `appuser` with localhost-bound Compose ports (`127.0.0.1`). |
