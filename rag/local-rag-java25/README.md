# Local RAG Studio — Java 25, Spring Boot 4, Angular 20, Ollama, Qdrant

A Docker-first educational RAG app. The backend follows hexagonal architecture: REST inbound adapter → application services → ports → PDFBox, Ollama and Qdrant outbound adapters. The frontend is a standalone Angular application served by nginx, which proxies `/api/` to Spring Boot.

## Prerequisites

- Docker Engine / Docker Desktop with the `docker compose` plugin. No local Java, Maven, Node, Angular CLI, Ollama or Qdrant installation required.
- Internet access for initial Docker images, Maven/npm packages, Ollama models, and the sample PDF.
- Recommended: **at least 8 GB available RAM** and **several GB of free disk**. CPU-only inference works but may be slow.

## Start

```bash
docker compose up --build -d
docker compose ps
docker compose logs -f model-init backend
```

Visit **http://localhost:8088**. First start can take many minutes while the `llama3.2:1b` and `nomic-embed-text` models are downloaded. The UI waits until model initialization and backend health checks pass. If port 8088 is in use, create a `.env` containing `WEB_PORT=8090` before starting.

1. Click **Index document** to download and index the example ThaiRecipes.pdf.
2. Ask a question such as *Which recipes use coconut milk?*
3. Expand **Retrieved sources** to inspect the chunks used for the answer.

## Stop and reset

```bash
docker compose down              # stop, retain models and Qdrant vectors
docker compose down -v           # delete both persistent volumes (destructive)
```

## Tests

The backend image build executes `mvn verify` (JUnit/Mockito unit tests). To run tests without installing Maven:

```bash
docker compose run --rm --no-deps --entrypoint sh backend -c 'echo "Backend unit tests already run in the Docker build stage"'
```

To rerun backend tests explicitly in a temporary Maven container:

```bash
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.11-eclipse-temurin-25 mvn -B test
```

Angular's HTTP client tests can run locally from `frontend`:

```bash
cd frontend
npm install
npm test
```

These are unit tests, not full end-to-end tests. Docker builds also compile the Angular application.

Playwright is installed in `frontend/package.json` (`@playwright/test` in `devDependencies`), so `npm install` installs it automatically.
Run Playwright **outside Docker** from your host machine (requires running stack at `http://localhost:8088` or set `E2E_BASE_URL`):

```bash
docker compose up --build -d
cd frontend
npm install
npm run test:e2e
```

Install Playwright browser binaries once on a new machine (or after clearing Playwright cache):

```bash
cd frontend
npm run playwright:install
```

## API

The UI uses nginx to proxy these endpoints to the backend:

- `POST /api/v1/documents` with `{ "url": "https://phi-public.s3.amazonaws.com/recipes/ThaiRecipes.pdf" }`
- `POST /api/v1/documents/pdf` as `multipart/form-data` with `file=<pdf>` for direct PDF upload ingestion
- `POST /api/v1/questions` with `{ "question": "Which recipes use coconut milk?" }`
- `POST /api/v1/questions/general` with `{ "question": "What is a common leave policy?" }` for non-RAG general answers

The backend is not published directly to the host. For API access from the host use `http://localhost:8088/api/v1/...`.

`POST /api/v1/questions` now includes grounding metadata:

- `grounded=true` and `source="rag"` when answer is supported by retrieved chunks.
- `grounded=false` and `source="internet"` when policy coverage is weak; response includes an advisory to verify with manager/HR.

## Services

| Service | Function | Host port |
|---|---|---|
| `frontend` | nginx serving Angular + API proxy | 8088 (configurable) |
| `backend` | Spring Boot 4 / Java 25 | internal 8080 |
| `qdrant` | Persistent vector index | internal 6333 |
| `ollama` | Embeddings and chat generation | internal 11434 |
| `model-init` | Downloads models once and exits | none |

## Design and security notes

- The sample ingestion adapter intentionally accepts **only** the fixed tutorial PDF URL to prevent arbitrary server-side URL fetching (SSRF).
- API validation and sanitized errors are enabled. Prompt contents and document text are not intentionally logged; Spring Boot emits ECS-style structured JSON logs.
- Qdrant data and Ollama model files persist in named Docker volumes.
- The same embedding model must be used at ingestion and query time. Changing models may require resetting the Qdrant collection.
- This is a **single-user development demo**, not a publicly accessible multi-tenant product. There is no authentication, rate limiting, malware scanning, arbitrary upload support, background indexing or streaming responses. Keep the published frontend bound to a trusted machine/network.
- Re-ingestion upserts deterministic chunk IDs but does not remove stale chunks if a document becomes shorter.
- The app requires the network for initial dependency/model downloads and to fetch the sample PDF. After initialization, question answering is local.

## Verification status

This archive contains the Docker build definitions, application source and tests. Docker and Maven were unavailable in the authoring environment, and npm dependency fetching timed out, so a full image build and end-to-end run **have not been verified**. Run `docker compose up --build -d` on a Docker-capable host and inspect the logs for dependency/version or startup issues before relying on the app.
