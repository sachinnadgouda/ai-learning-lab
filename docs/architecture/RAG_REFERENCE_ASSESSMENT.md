# Internal Engineering Reference Assessment: `rag/local-rag-java25`

This assessment records concrete engineering patterns and lessons drawn from inspecting the existing reference project `rag/local-rag-java25` (HR Q&A Portal) before designing Set 1.

## 1. Architecture & Implementation Summary

- **Stack:** Java 25, Spring Boot 4.0.7, Maven, Apache PDFBox 3.0.5, Angular 20.3 (Vitest + Playwright), Nginx, Ollama (`nomic-embed-text:v1.5`, `llama3.2:1b`), Qdrant `v1.15.5`.
- **Structure:** Clean hexagonal layout (`domain/`, `application/`, `application/port/`, `infrastructure/`, `api/`).
- **Flow:** PDF upload -> `PdfReaderAdapter` -> `TextChunker` -> `OllamaAdapter.embedAll` -> `QdrantAdapter.upsert` -> open questions rechecked via `QuestionService`; employee questions -> vector search + relevance threshold + `QuestionCoverage` heuristic -> grounded answer or HR review queue.

## 2. Patterns Worth Reusing

| Pattern | Evidence in `rag/local-rag-java25` | Application in Set 1 |
|---|---|---|
| Thin hexagonal boundaries | Pure domain records (`Question`, `Chunk`, `Match`), narrow outbound ports (`EmbeddingPort`, `AnswerPort`, `VectorStorePort`) | Carried forward in all 4 Set 1 apps (TypeScript, Python, Kotlin, Go) without framework coupling in domain layers. |
| Startup-validated typed config | `@Validated @ConfigurationProperties("rag") RagProperties` with Bean Validation & sensible defaults | Zod config validation (TS), Pydantic Settings (Python), validated Kotlin config, Go startup validator. |
| RFC 9457 Problem Details & error isolation | `ApiErrorHandler` maps `UpstreamUnavailableException` to 502 with generic client detail while preserving 400/404/405/413/415 | Consistent RFC 9457 error contracts across HTTP APIs and structured error codes in MCP. |
| Deterministic grounding gate before LLM call | `RagService` checks vector similarity AND `QuestionCoverage` before calling `AnswerPort`; abstains on no match | Applied to Insurance Claims Q&A (hybrid RRF + abstention + citation verification) and deterministic-first rules across Apps 1, 3, and 4. |
| Defensive prompt framing | `OllamaAdapter` explicitly marks `REFERENCE TEXT` as untrusted data, never instructions | Strengthened across Set 1 with input fencing, injection detection heuristics, and strict structured-output schema validation. |
| Content-addressed idempotent IDs | `UUID.nameUUIDFromBytes(pdfBytes)` and `documentId:ordinal` | Applied to document ingestion hashes (App 2), incremental file hashing (App 3), and event idempotency keys (App 4). |
| Race-aware recheck & atomic writes | `AtomicLong indexVersion` in `QuestionService`; temp-file + `ATOMIC_MOVE` in `FileQuestionStore` | Applied to concurrent ingestion/event ordering in Apps 2–4. |
| Contract-level adapter tests & secure Docker | `MockRestServiceServer` in `OllamaAdapterTest`/`QdrantAdapterTest`; multi-stage non-root Dockerfiles; pinned image tags; `127.0.0.1` port bindings | Adopted across Set 1 with real HTTP contract tests, non-root containers, pinned base images, and localhost-bound Compose ports. |

## 3. Weaknesses & Limitations Not to Repeat

1. **No server-side authentication or authorisation:** In `local-rag-java25`, Employee vs HR is a UI toggle and `/api/v1/hr/**` is unauthenticated. **Set 1 improvement:** Enforce server-side auth/scopes, explicit permission modes (`read-only` vs `write-with-confirmation`), role checks, and human-only approval invariants.
2. **Whole-file JSON rewrite persistence:** `FileQuestionStore` rewrites an entire JSON file under a JVM `synchronized` lock. **Set 1 improvement:** Use relational persistence (SQLite with WAL mode / PostgreSQL support) and versioned schema migrations for audit trails, claims, scan history, and event/DLQ state.
3. **No upstream retry/backoff/rate-limit handling:** `OllamaAdapter` and `QdrantAdapter` configure timeouts (good) but lack backoff/jitter retries or `429`/`Retry-After` handling. **Set 1 improvement:** Implement explicit retry with exponential backoff + jitter, rate-limit handling, and circuit breakers where upstream failures are expected.
4. **Limited observability and no AI telemetry:** Logs lack correlation IDs, Prometheus metrics, and persistent AI invocation latency/token records. **Set 1 improvement:** Add structured JSON logging with correlation IDs, secret redaction, `/metrics` & `/health`/`/ready`, and persistent audit trails.
5. **PDF-only flattened chunking without page provenance:** `TextChunker` strips page boundaries and `PdfReaderAdapter` checks only byte length. **Set 1 improvement (App 2):** Preserve exact document/page/offset provenance, validate file signatures/magic bytes, and support multi-format ingestion plus OCR abstraction.
6. **Single hardcoded AI adapter & no offline evaluation suite:** `OllamaAdapter` is the sole implementation and there is no offline AI eval dataset or CI workflow. **Set 1 improvement:** Pluggable AI provider configuration, fixed offline evaluation datasets/runners, and path-filtered GitHub Actions workflows for every Set 1 application.
