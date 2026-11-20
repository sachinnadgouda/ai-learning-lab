# HR Q&A Portal

A local HR demo built with Java 25, Spring Boot 4, Angular 20, Ollama, and Qdrant. Use **sample documents and questions only**. The Employee / HR toggle is not access control; anyone who can open the demo can switch to HR view.

## Run the demo

Install Docker Engine or Docker Desktop with the Compose plugin. The first run downloads images, dependencies, and Ollama models. Allow several GB of disk space and at least 8 GB of available RAM.

```bash
docker compose up --build -d
docker compose ps
```

Open **http://localhost:8088**. The frontend is bound to the local machine. To change the port, set `WEB_PORT=8090` in `.env`.

1. In **Employee view**, leave **Search HR policy documents** checked and ask, for example, “How many days of annual leave do employees receive?” If nothing matches, the question appears in HR's **Needs review** queue instead of receiving a generic answer.
2. Switch to **HR view** and upload a text-based policy PDF containing the answer. The portal checks open questions against the updated index and shows matching passages with their filenames. A match does **not** close the question.
3. Review the passages, write an HR reply, and select **Send reply and mark answered**. The employee can see the reply in **My questions** from the original browser, after switching back or refreshing.
4. To try general AI guidance, uncheck **Search HR policy documents**. That mode uses local model knowledge, not the web or company policy. HR sees these questions under **Not checked**, outside the review queue.

## Data and limitations

- Uploads must be readable, unencrypted, text-based PDFs no larger than 10 MB. Scanned pages without selectable text are not supported. PDFs are uploaded directly; there is no URL field.
- Matching uses a similarity and keyword-coverage heuristic, so a match is not proof that the policy answers the question. Review the cited passages before replying. Documents are added to the index; uploading identical bytes again reuses the same index entries. Changed PDFs are separate documents.
- HR policy vectors use a fresh collection, separate from the old recipe index. Question history and HR replies are stored in the `hr_data` Docker volume; Ollama and Qdrant retain their own volumes. Employee history is associated with a browser ID stored in local storage, not an account. Clearing site data or changing browser or port loses that browser's link to its history, but does not delete HR records.
- `docker compose down` stops the app and retains its data. `docker compose down -v` **deletes** persisted questions, vectors, and downloaded models.

## API

The Angular frontend calls Spring Boot through nginx. `GET /api/v1/questions` and `POST /api/v1/questions` require a UUID in `X-Demo-Browser-Id`.

| Method and path | Request or result |
|---|---|
| `POST /api/v1/documents/pdf` | Multipart `file`; returns indexed passage, match, and recheck failure counts. |
| `POST /api/v1/questions` | `{ "question": "How much annual leave do I get?", "searchPolicy": true }`; stores and returns the submission. |
| `GET /api/v1/questions` | Question history for the submitting browser ID. |
| `GET /api/v1/hr/questions` | All submissions and their policy and resolution states. |
| `PUT /api/v1/hr/questions/{id}/reply` | `{ "reply": "Employees receive 25 days." }`; stores the reply and marks it answered. |

## Tests

Run `mvn verify` from this directory with JDK 25. The backend Docker build also runs Maven verification. From `frontend`, run `npm ci`, then `npm test` and `npm run build`.

Run the browser tests against a disposable running Compose stack: they submit sample questions and index a generated PDF. From `frontend`, run `npm run playwright:install` once, then `npm run test:e2e`. Set `E2E_BASE_URL` if the test stack isn't on `http://localhost:8088`.
