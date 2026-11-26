# ADR-001: Independent Application Boundaries Without a Shared Monorepo Framework

- **Status:** Accepted
- **Date:** 2026-11-26

## Context

`ai-learning-lab` hosts polyglot AI applications spanning Java, TypeScript, Python, Kotlin, and Go. A common temptation in multi-project repositories is to build a shared internal SDK, unified build wrapper, or shared runtime compose stack before proving common abstractions.

## Alternatives Considered

1. **Shared polyglot monorepo runner (Nx/Bazel) + shared platform services:** High maintenance friction across five language ecosystems and couples unrelated apps during CI and local runs.
2. **Completely independent application directories with repository-level standards and path-filtered CI (Chosen):** Each application owns its own build tool (`npm`, `uv`, Gradle, `go`), dependencies, tests, Dockerfile, Compose stack, and docs.

## Decision

Keep every application self-contained under its own directory (`rag/local-rag-java25`, `self-evolving-agents`, and `applications/<app-name>`). Maintain consistency through shared engineering standards (`docs/engineering-standards/STANDARDS.md`), `.editorconfig`, security conventions, and per-application path-filtered GitHub Actions workflows rather than shared runtime code.

## Trade-offs & Limitations

- Some boilerplate (Dockerfiles, RFC 9457 error formatting, HTTP retry helpers, CI workflow YAML) is repeated in each language's idiomatic form.
- That duplication is intentional: any single application can be inspected, run, or extracted into its own repository with zero cross-project coupling.
