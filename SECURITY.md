# Security Policy

## Scope

This repository contains independent, local-first AI engineering reference applications designed with production-oriented security foundations. They are portfolio and engineering reference systems, not pre-certified deployments for regulated production environments without organisational review.

## Security Controls Applied Across Applications

- **No committed secrets:** Configuration is externalised via environment variables with `.env.example` templates. Real secrets and tokens must never be committed.
- **Server-side access control:** Privileged APIs and write-capable tools enforce authentication, role/permission checks, and explicit confirmation server-side.
- **Prompt-injection defence:** External repository content, uploaded documents, scanned source code, and operational event payloads are fenced as untrusted data and validated against strict output schemas.
- **Human-in-the-loop governance:** LLMs never autonomously execute consequential actions (write GitHub actions without confirmation, approve/reject/pay insurance claims, alter deterministic architecture findings, or execute supply-chain operational changes without human approval).
- **Safe input & file handling:** File size limits, magic-byte/content validation, path-traversal prevention, bounded queues, and safe error handling (RFC 9457 Problem Details without stack-trace leakage).
- **Auditability & log hygiene:** Sensitive actions and AI invocations are audited; credentials, tokens, and unnecessary PII are redacted from logs and audit records.

## Reporting a Vulnerability

If you discover a security issue in any application in this repository:

1. Do **not** open a public GitHub issue containing exploit details or credentials.
2. Report it privately via GitHub Security Advisories on `sachinnadgouda/ai-learning-lab`, including:
   - Affected application and file paths
   - Reproduction steps or proof of concept
   - Expected vs actual security boundary behaviour
   - Suggested remediation if known

Each application under `applications/` also includes an application-specific `docs/SECURITY.md` describing its trust boundaries, sensitive data handling, auth model, AI threat model, and known limitations.
