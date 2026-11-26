# Security & Threat Model — Insurance Claims Intelligence

## 1. Threat Model & Mitigations

| Threat | Mitigation Implemented |
|---|---|
| **Prompt Injection via Claim Documents** (e.g., malicious PDF invoice instructing the AI to approve for $50,000) | 1. Deterministic regex/pattern detector flags instruction overrides, approval forcing, role hijacks, and exfiltration attempts.<br>2. Injection lines are stripped before deterministic fact extraction.<br>3. Retrieved chunks are wrapped in escaped `<untrusted_claim_document>` XML data fences with a strict security preamble.<br>4. Flagged payloads raise a `HIGH` severity `PROMPT_INJECTION_DETECTED` finding (+25 advisory risk). |
| **Autonomous AI Payout / Approval** | Domain invariant `Claim.record_review_decision` rejects `ActorType.AI_SYSTEM` and `ActorType.SERVICE` with `AutonomousDecisionProhibitedError` (HTTP 403), and `ReviewService` records `REVIEW_DECISION_BLOCKED` in the audit trail. |
| **Privilege Escalation (Adjuster approving own claim)** | Server-side `X-API-Key` validation via `hmac.compare_digest` enforces RBAC: only human `reviewer` and `admin` roles may record decisions. |
| **Malicious / Polyglot / Oversized Uploads** | `MAX_UPLOAD_BYTES` bounded streaming (`413`), path-traversal filename rejection (`400`), magic-byte header validation for PDF/PNG/JPEG, binary-signature rejection in text files, `Pillow.verify()` and `pypdf` validation. |
| **Hallucinated / Fabricated Citations** | `QaService` and `ExtractionService` verify BOTH that every `chunk_id` belongs to the retrieved claim chunks AND that `cite.quote` is grounded in that specific chunk's source text (`verify_citation_quote`: verbatim/ellipsis match or exact numeric preservation + >=85% lexical overlap); fabricated, numerically altered, or misattributed citations are dropped and ungrounded Q&A responses downgrade to `UNANSWERABLE`. |
| **PII & Secret Leakage in Logs / Audits / Errors** | `domain/redaction.py` masks SSNs, emails, phone numbers, card numbers, Bearer tokens, API keys, and policy suffixes across JSON logs, audit metadata, and RFC 9457 error responses. |
| **Container & Network Exposure** | Non-root user (`UID 10001`), pinned base images, localhost-bound Compose ports (`127.0.0.1`), narrow CORS allowlist (`GET`/`POST`, explicit headers). |

## 2. Production Hardening Checklist

- Replace all `dev-*-key-change-me` API keys with high-entropy secrets managed by your secret store (or integrate an enterprise OIDC/JWT gateway in front of `auth.py`).
- Enforce TLS termination at the ingress proxy and restrict `CORS_ALLOW_ORIGINS` to exact frontend origins.
- Set `APP_ENV=production` and use PostgreSQL with encrypted connections (`sslmode=require`) and automated backups.
