# HR Q&A Portal

A local HR demo built with Java 25, Spring Boot 4, Angular 20, Ollama, and Qdrant. Use **sample documents and questions only**. The Employee / HR toggle is not access control; anyone who can open the demo can switch to HR view.

## What this project is

A Retrieval-Augmented Generation (RAG) demo for HR questions. Employees ask questions; the app answers only from uploaded HR policy PDFs. If nothing relevant is found, the question goes to an HR review queue where HR can upload a better document and send a reply.

```
Angular UI --(nginx /api)--> Spring Boot backend --> Ollama  (embeddings + chat model)
                                     |
                                     +-----------> Qdrant  (vector search)
                                     +-----------> questions.json (question history and HR replies)
```

Flow: PDF upload -> text extraction -> chunking -> embedding (Ollama) -> stored in Qdrant. A question is embedded, the top-k similar chunks are retrieved, and the chat model answers from those chunks only.

The backend uses ports and adapters (`application/port` and `infrastructure`). Ollama is used only through `OllamaAdapter`, and Qdrant only through `QdrantAdapter`.

## Run with Docker

Install Docker Engine or Docker Desktop with the Compose plugin. The first run downloads images, dependencies, and Ollama models. Allow several GB of disk space and at least 8 GB of available RAM.

```bash
docker compose up --build -d
docker compose ps
```

Open **http://localhost:8088**. The frontend is bound to the local machine. To change the port, copy `.env.example` to `.env` and set `WEB_PORT=8090`.

1. In **Employee view**, leave **Search HR policy documents** checked and ask, for example, “How many days of annual leave do employees receive?” If nothing matches, the question appears in HR's **Needs review** queue instead of receiving a generic answer.
2. Switch to **HR view** and upload a text-based policy PDF containing the answer. The portal checks open questions against the updated index and shows matching passages with their filenames. A match does **not** close the question.
3. Review the passages, write an HR reply, and select **Send reply and mark answered**. The employee can see the reply in **My questions** from the original browser, after switching back or refreshing.
4. To try general AI guidance, uncheck **Search HR policy documents**. That mode uses local model knowledge, not the web or company policy. HR sees these questions under **Not checked**, outside the review queue.

## Run locally without Docker

Prerequisites: JDK 25, Maven 3.9+, Node.js 20+ with npm, [Ollama](https://ollama.com/download), and [Qdrant](https://qdrant.tech/documentation/guides/installation/) (a downloaded release binary works; no Docker needed). Use four terminals.

1. **Ollama** (listens on `localhost:11434`; the desktop app on macOS starts it automatically):
   ```bash
   ollama serve                       # skip if already running
   ollama pull nomic-embed-text:v1.5
   ollama pull llama3.2:1b
   ```
2. **Qdrant** (REST on `localhost:6333`; data goes to `./storage` in the directory you start it from):
   ```bash
   ./qdrant
   ```
3. **Backend** from this directory (port 8080; defaults already point to `localhost`):
   ```bash
   mvn spring-boot:run
   curl http://localhost:8080/actuator/health
   ```
   Questions are stored in `./data/questions.json`. Override with `OLLAMA_URL`, `QDRANT_URL`, `RAG_QUESTION_FILE`, or `SERVER_PORT` if needed.
4. **Frontend** from `frontend` (the app calls relative `/api`, which `proxy.conf.json` forwards to the backend):
   ```bash
   npm ci
   npx ng serve --proxy-config proxy.conf.json
   ```
   Open **http://localhost:4200**.

## Moving to the cloud (replacing Ollama)

Ollama is used for two things: **embeddings** (`EmbeddingPort`) and **chat answers** (`AnswerPort`, `GeneralAnswerPort`). `OllamaAdapter` is the only class that implements them, so going to the cloud means adding another adapter and nothing else in the domain or API changes.

Steps (the same for any provider):

1. Add a cloud adapter, e.g. `BedrockAdapter` or `VertexAiAdapter`, implementing the three ports. Keep the system prompts from `OllamaAdapter`.
2. Select it by property or profile, e.g. `rag.provider=ollama|bedrock|vertex` with `@ConditionalOnProperty` on each adapter (or `@Profile`), and add the new settings (region, project, model IDs) to `RagProperties` and `application.yml`.
3. **Re-index all documents.** Embedding models produce different vector sizes (`nomic-embed-text` is 768). Qdrant collections have a fixed dimension, so use a new `rag.collection` name and re-upload the PDFs. Never mix models in one collection.
4. Authenticate with the platform identity (IAM role / service account), not API keys in the repo or `.env`.
5. Deploy: backend as a container (ECS Fargate or Cloud Run), frontend on nginx or a CDN/static bucket routing `/api` to the backend, and Qdrant on [Qdrant Cloud](https://qdrant.tech/cloud/) or self-hosted. Set `QDRANT_URL` accordingly (and add an API key header in `QdrantAdapter` for Qdrant Cloud). `questions.json` is a local file; move it to a database or bucket when running more than one instance, since container disks are ephemeral.
6. Drop the `ollama` and `model-init` services from the cloud deployment.

### AWS

| Ollama role | AWS replacement |
|---|---|
| Embeddings (`/api/embed`) | Amazon Bedrock: Titan Text Embeddings V2 (`amazon.titan-embed-text-v2:0`, 256/512/1024 dims) or Cohere Embed, via `InvokeModel` |
| Chat (`/api/chat`) | Amazon Bedrock Converse API with Anthropic Claude or Amazon Nova (system prompt + user message maps directly) |
| Auth | IAM role on the ECS task / EKS service account with `bedrock:InvokeModel`; SDK: `software.amazon.awssdk:bedrockruntime` |
| Vector store (optional) | Qdrant Cloud/ECS, or swap `QdrantAdapter` for OpenSearch Serverless or Aurora PostgreSQL + pgvector |

Enable model access in the Bedrock console for your region first.

### GCP

| Ollama role | GCP replacement |
|---|---|
| Embeddings | Vertex AI embeddings, e.g. `text-embedding-005` (768 dims) |
| Chat | Gemini on Vertex AI (`generateContent`, system instruction + user content) |
| Auth | Service account with `roles/aiplatform.user`, picked up through Application Default Credentials; SDK: `com.google.cloud:google-cloud-vertexai` |
| Vector store (optional) | Qdrant Cloud/GKE, or swap for Vertex AI Vector Search or Cloud SQL/AlloyDB + pgvector |

Because `text-embedding-005` is also 768-dimensional, a GCP switch is easiest, but the vectors still come from a different model, so re-index anyway.

### Things to check before sending HR data to a cloud model

Confirm data residency/region, that your provider does not train on prompts, and logging of prompts. Update the SREQ/security approval if your company requires it. Expect cost per token and per request, and add retries/rate-limit handling in the adapter, mapping failures to `UpstreamUnavailableException` (HTTP 502).

## Data and limitations

- Uploads must be readable, unencrypted, text-based PDFs no larger than 10 MB. Scanned pages without selectable text are not supported. PDFs are uploaded directly; there is no URL field.
- Matching uses a similarity and keyword-coverage heuristic, so a match is not proof that the policy answers the question. Review the cited passages before replying. Documents are added to the index; uploading identical bytes again reuses the same index entries. Changed PDFs are separate documents.
- HR policy vectors use a fresh collection, separate from the old recipe index. Question history and HR replies are stored in the `hr_data` Docker volume; Ollama and Qdrant retain their own volumes. Employee history is associated with a browser ID stored in local storage, not an account. Clearing site data or changing browser or port loses that browser's link to its history, but does not delete HR records.
- `docker compose down` stops the app and retains its data. `docker compose down -v` **deletes** persisted questions, vectors, and downloaded models.

## Configuration

Backend settings live under `rag.*` in `src/main/resources/application.yml` and are validated at startup (`RagProperties`): models, retrieval thresholds, chunking, answer length (`answer-max-tokens`), batch sizes, and HTTP timeouts. Any of them can be overridden with environment variables such as `RAG_TOP_K`. Compose activates the `docker` profile, which switches console logging to ECS JSON. Models are pinned (`nomic-embed-text:v1.5`, `llama3.2:1b`); if you change them in `application.yml`, change the `ollama pull` line in `compose.yaml` too.

## API

The Angular frontend calls Spring Boot through nginx. `GET /api/v1/questions` and `POST /api/v1/questions` require a UUID in `X-Demo-Browser-Id`.

| Method and path | Request or result |
|---|---|
| `POST /api/v1/documents/pdf` | Multipart `file`; returns indexed passage, match, and recheck failure counts. |
| `POST /api/v1/questions` | `{ "question": "How much annual leave do I get?", "searchPolicy": true }`; stores and returns the submission. |
| `GET /api/v1/questions` | Question history for the submitting browser ID. |
| `GET /api/v1/hr/questions` | All submissions and their policy and resolution states. |
| `PUT /api/v1/hr/questions/{id}/reply` | `{ "reply": "Employees receive 25 days." }`; stores the reply and marks it answered. |

Errors use RFC 9457 problem details: `400` invalid input, `413` oversized upload, `502` when Ollama or Qdrant fails, `500` for unexpected errors. Framework errors such as `404`/`405`/`415` keep their own status.

## Tests

Run `mvn verify` from this directory with JDK 25. The backend Docker build also runs Maven verification. From `frontend`, run `npm ci`, then `npm test` and `npm run build`.

Run the browser tests against a disposable running Compose stack: they submit sample questions and index a generated PDF. From `frontend`, run `npm run playwright:install` once, then `npm run test:e2e`. Set `E2E_BASE_URL` if the test stack isn't on `http://localhost:8088`.
