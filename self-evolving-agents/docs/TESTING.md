# Testing

| Layer | Tests | Tooling |
|---|---|---|
| Domain | `TrafficPolicyTest` (bounds, no-op detection), `ReplayEvaluatorTest`, `AdmissionAndGovernanceTest` | JUnit 5 |
| Application | `ProposePolicyUseCaseTest` (decisions incl. increase/decrease/no-op/insufficient data, flags, failure/timeout, redaction, persistent lessons), `GovernanceUseCasesTest` (approval binding, rollback, flags, validation), `EvolutionLifecycleScenarioTest` (deterministic end-to-end, scripted advisor, no LLM) | JUnit 5, in-memory fakes (`support/Fakes`, `Harness`) |
| Architecture | `HexagonalArchitectureTest` (domain/application framework-free, adapters one-way) | ArchUnit |
| Infrastructure | `OllamaResponseParserTest`, `OllamaPolicyAdvisorTest` (real HTTP against a local stub server: exact payload, timeout, retry, unreachable), `JdbcRepositoriesTest` (real Flyway migration on H2 in PostgreSQL mode), `RedisAdaptersIT` (Lua admission, policies, flags; Redis via **Testcontainers**, skipped without Docker), `ApiSecurityAndErrorsTest` (full Spring context + MockMvc: 401/403, ProblemDetail, CORS, public endpoints) | JUnit 5, Spring Boot Test, Testcontainers |
| Angular | API service, auth interceptor, JSON formatter, AI Inspector rendering/compare/filter, Policies (approval disabled until evaluated, confirmation, fingerprint binding, rollback), Settings (feature flags), Overview, experiment rows | Vitest via `ng test` |
| Docker | `scripts/compose-smoke.sh` (build, start, health of every service, proxy, metrics, API smoke) and `scripts/smoke.sh` | shell |

## Commands

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS example
./gradlew test                                       # backend (Testcontainers test needs Docker)
cd frontend && npm ci && npm test                    # Angular
./gradlew :frontend:ngTest :frontend:ngBuild         # Angular through Gradle
sh scripts/compose-smoke.sh                          # needs Docker + .env
```

## Known limits

- PostgreSQL itself is not exercised by automated tests (H2 in PostgreSQL mode is used); the Compose smoke test runs the same migration on real PostgreSQL.
- Automated tests never call a real LLM. The live Ollama path was verified for connectivity and failure/timeout handling only (see [REFACTORING_CHANGELOG.md](REFACTORING_CHANGELOG.md#verification-status)).
- No browser end-to-end tests of the Angular UI.
