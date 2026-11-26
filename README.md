# AI Learning Lab

A polyglot portfolio of **independent, production-oriented AI engineering applications**. Each application tackles a concrete operational problem using clean architecture, deterministic business logic paired with governed AI workflows, explicit security boundaries, automated evaluation suites, and reproducible local deployment.

## Independent-Application Philosophy

Rather than coupling projects into a monolithic framework, every application in this repository is a self-contained system with its own domain model, build toolchain, persistence layer, test suite, Docker configuration, CI workflow, and technical documentation. You can clone the repository and run any single application locally without starting any other application or provisioning cloud services.

See [docs/architecture/OVERVIEW.md](docs/architecture/OVERVIEW.md), [docs/engineering-standards/STANDARDS.md](docs/engineering-standards/STANDARDS.md), and [docs/decisions/](docs/decisions/) for repository-wide architecture notes and ADRs.

---

## Application Catalogue

### Existing Applications (Preserved)

| Application | Stack | Business Problem & Key Capabilities | Architecture Highlights | Documentation |
|---|---|---|---|---|
| **HR Q&A Portal** | Java 25, Spring Boot 4, Angular 20, Ollama, Qdrant, PDFBox | Grounded HR policy Q&A over uploaded PDFs with similarity + keyword-coverage gating; unanswered questions route to an HR review queue and are rechecked automatically on new policy uploads. | Hexagonal ports & adapters, content-addressed chunk IDs, RFC 9457 errors, race-aware recheck counter, Playwright E2E. | [README](rag/local-rag-java25/README.md) |
| **Governed Self-Evolving Traffic Control** | Java 21, Spring Boot 4, Angular 22, Redis, PostgreSQL, Ollama, Prometheus, Grafana, k6 | Flash-sale traffic admission & waiting-room policy control where an LLM proposes candidate policies that are independently validated, replayed deterministically, and human-approved or rolled back. | Hexagonal architecture verified by ArchUnit, redacted AI invocation audit store, Redis runtime flags, full Prometheus/Grafana observability. | [README](self-evolving-agents/README.md) |

### Set 1 Applications

| Application | Primary Language & Stack | Business Problem & Key Capabilities | Architecture Highlights | Documentation |
|---|---|---|---|---|
| **1. Engineering Intelligence MCP** | TypeScript (Node 22+), `@modelcontextprotocol/sdk`, Zod, SQLite, Vitest | Exposes GitHub repository, PR, issue, commit, CI failure, release, and code-ownership intelligence through MCP with permission-aware read/write separation, two-phase confirmation for write actions, and an interactive MCP demo CLI. | Clean separation of domain, GitHub REST adapter (timeouts, pagination, rate-limit backoff, circuit breaker), MCP stdio/HTTP server, prompt-injection fencing, and persistent SQLite audit trail. | [README](applications/engineering-intelligence-mcp/README.md) |
| **2. Insurance Claims Intelligence** | Python 3.13, FastAPI, Pydantic v2, SQLAlchemy + Alembic, SQLite/PostgreSQL, PyPDF, Pillow | Multi-format claim document ingestion, OCR/extraction abstraction, hybrid BM25 + semantic RRF retrieval, evidence-backed Q&A, cross-document entity resolution, inconsistency & missing-evidence detection, risk indicators, and human review workflow. | Page-level source provenance, explicit `EXTRACTED_FACT` vs `INFERRED` vs `UNCERTAIN` typing, domain-enforced prohibition on autonomous AI claim decisions, RBAC API keys, offline retrieval/injection eval suite. | [README](applications/insurance-claims-intelligence/README.md) |
| **3. Software Architecture Auditor** | Kotlin (JVM 21), Gradle Wrapper, Ktor 3, SQLite + Flyway, JUnit 5 | Deterministic multi-language (Kotlin, Java, TypeScript, Python, Go) dependency graph construction, Tarjan SCC circular dependency detection, Robert C. Martin coupling metrics, configurable architectural rules, incremental hashing scans, scan history diffing, SARIF/Markdown/Mermaid exports, and AI refactoring explanations. | Deterministic analysis is strictly authoritative and immutable; AI suggestions are stored separately behind untrusted-source prompt fencing; bounded file/memory scanning with CLI + HTTP API. | [README](applications/software-architecture-auditor/README.md) |
| **4. Supply Chain Risk Intelligence** | Go 1.24+, `net/http`, `log/slog`, SQLite (`modernc.org/sqlite`), optional Redis Streams | Event-driven ingestion of supplier, route, shipment, and inventory events with bounded worker pools, idempotent deduplication, out-of-order watermark handling, retry + Dead-Letter Queue (DLQ) replay, cross-entity risk correlation, shortage projection, AI investigation summaries, and human-approved operational change proposals. | Idiomatic Go concurrency verified with `go test -race`, deterministic risk & shortage scoring separated from AI summaries, server-enforced human approval gate, Prometheus metrics, and CLI event simulator. | [README](applications/supply-chain-risk-intelligence/README.md) |

---

## Repository Structure

```text
ai-learning-lab/
├── README.md
├── LICENSE
├── CONTRIBUTING.md
├── SECURITY.md
├── .editorconfig
├── .gitignore
├── applications/
│   ├── engineering-intelligence-mcp/      # Set 1 · TypeScript
│   ├── insurance-claims-intelligence/     # Set 1 · Python
│   ├── software-architecture-auditor/     # Set 1 · Kotlin
│   └── supply-chain-risk-intelligence/    # Set 1 · Go
├── rag/
│   └── local-rag-java25/                  # Existing · Java 25 / Angular
├── self-evolving-agents/                  # Existing · Java 21 / Angular
├── docs/
│   ├── architecture/                      # Portfolio overview & RAG reference assessment
│   ├── decisions/                         # Repository-level ADRs
│   ├── engineering-standards/             # Cross-cutting engineering standards
│   └── roadmap/                           # Three-set implementation roadmap
└── .github/
    └── workflows/                         # Path-filtered CI per application
```

---

## Quick Local Setup

Each application can be run and tested independently without paid external model APIs:

| Application | Local Test Command | Local Run / Demo Command |
|---|---|---|
| Engineering Intelligence MCP | `cd applications/engineering-intelligence-mcp && npm ci && npm test` | `npm run build && npm run cli -- demo` |
| Insurance Claims Intelligence | `cd applications/insurance-claims-intelligence && uv sync --python 3.13 --all-extras && uv run pytest` | `uv run python -m claims_intel.cli migrate && uv run python -m claims_intel.cli seed && uv run python -m claims_intel.cli serve` |
| Software Architecture Auditor | `cd applications/software-architecture-auditor && ./gradlew check` | `./gradlew installDist && ./build/install/arch-auditor/bin/arch-auditor audit examples/violating-monolith --rules examples/rules.yaml` |
| Supply Chain Risk Intelligence | `cd applications/supply-chain-risk-intelligence && go test -race ./...` | `go run ./cmd/server --seed` (or `go run ./cmd/simulator`) |
| HR Q&A Portal (existing) | `cd rag/local-rag-java25 && mvn verify` | `cd rag/local-rag-java25 && docker compose up --build -d` |
| Self-Evolving Traffic Control (existing) | `cd self-evolving-agents && ./gradlew test` | `cd self-evolving-agents && docker compose up --build -d` |

See each application's README for prerequisites, `.env.example` configuration, Docker Compose instructions, API/MCP contracts, and AI evaluation commands.

---

## Engineering Principles

- **Deterministic core, governed AI:** Access control, graph algorithms, risk scoring, coverage rules, and approval state machines are deterministic. AI components augment with synthesis, extraction, and explanation under strict output schema validation.
- **Explicit security boundaries:** Untrusted repository text, claim documents, source code, and event payloads are fenced against prompt injection. Consequential mutations require server-enforced human confirmation or approval.
- **Observable & auditable:** Structured JSON logs with correlation IDs, health/readiness probes, metrics, secret redaction, and persistent audit trails.
- **Offline-reproducible testing:** Every Set 1 application includes unit, integration, contract, and fixed-dataset AI evaluation tests that run in CI with zero paid API credentials.
- **Honest documentation:** Architecture diagrams reflect actual code; known limitations are stated explicitly without fabricated benchmarks.

---

## Roadmap (Future Sets)

The following applications are **Planned** for future implementation sets and are not yet implemented:

### Set 2 — Planned

| Application | Planned Language | Status |
|---|---|---|
| Financial Reconciliation Engine | Java | **Planned** |
| Cloud Cost Optimisation Agent | Go | **Planned** |
| Clinical Trial Intelligence | Python | **Planned** |
| Procurement Contract Intelligence | Python | **Planned** |

### Set 3 — Planned

| Application | Planned Language | Status |
|---|---|---|
| Customer Experience Intelligence | TypeScript | **Planned** |
| Personal Knowledge Graph | Kotlin | **Planned** |

See [docs/roadmap/ROADMAP.md](docs/roadmap/ROADMAP.md) for the complete roadmap.

---

## Security & Contributing

- Security model and vulnerability reporting: [SECURITY.md](SECURITY.md)
- Contribution workflow and standards: [CONTRIBUTING.md](CONTRIBUTING.md)
- License: [Apache 2.0](LICENSE)
