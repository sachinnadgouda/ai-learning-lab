# Contributing to AI Learning Lab

Thank you for your interest in contributing. This repository is a portfolio of independent, production-oriented AI engineering applications.

## Repository Principles

1. **Independent applications:** Every application under `applications/`, `rag/`, or top-level existing directories owns its own domain model, build system, tests, configuration, Docker setup, and documentation. Do not introduce cross-application runtime dependencies or premature shared frameworks.
2. **Deterministic first:** Keep deterministic business rules, risk calculations, graph algorithms, and access control outside LLMs. Use AI where it adds concrete value (synthesis, structured extraction, explanation, grounded Q&A) with schema validation and human oversight.
3. **No fake workflows:** Never commit stubbed business paths, hardcoded AI success responses, or unverified metrics. Use fakes/mocks strictly inside tests, and label demonstration datasets explicitly.
4. **Security by design:** Never commit `.env` files, API keys, tokens, or real personal/customer data. Validate all external inputs and treat external documents, repositories, and event payloads as untrusted input.

## Working on an Application

1. Work inside a single application directory and follow its README for local setup and test commands.
2. Keep existing projects (`rag/local-rag-java25`, `self-evolving-agents`) intact unless addressing a verified bug.
3. Follow `.editorconfig` formatting conventions and run the application's formatter, linter, type checker, unit tests, integration tests, and AI evaluation suite before opening a pull request.
4. Update the application's `README.md`, `docs/`, and ADRs when changing public contracts, architecture boundaries, or security controls.

## Pull Request Checklist

- [ ] Scope is focused on one application or repository-level documentation/automation.
- [ ] All unit, integration, contract, and evaluation tests pass locally without requiring paid live model APIs.
- [ ] No secrets, `.env` files, build artifacts, `node_modules/`, `.venv/`, or test output directories are staged.
- [ ] Documentation and Mermaid diagrams match the implemented code.
- [ ] Security boundaries and known limitations are documented accurately.
