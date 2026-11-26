> **Historical document (v0.1).** The `/api/*` endpoints it mentions were replaced by `/api/v1` in v0.2 (see [API.md](API.md) and [REFACTORING_CHANGELOG.md](REFACTORING_CHANGELOG.md)). Behaviour descriptions may be outdated.

# Adding an AI Advisor Without Giving It the Keys

The first version of this demo could suggest a smaller admission budget and enable a waiting room, but its suggestion came from a hard-coded formula. That is useful as a control-plane demonstration, not evidence of an intelligent system learning.

This iteration introduces a locally hosted Ollama model. It receives the active policy and the admission counters collected by the running Spring Boot process. The model returns a small JSON proposal: a new admission rate, a maximum queue size, and a rationale. The service parses the JSON, enforces strict numeric bounds, and stores the result as a **candidate**, never as the active configuration.

An operator can disable the AI advisor through a Redis-backed feature flag. This flag is off by default. The same API exposes the current state so that demonstrations do not depend on hidden environment variables. When disabled, proposal requests are refused. When enabled, Ollama is still only an advisor.

Why keep the approval step? Traffic management is a safety-critical control loop. An LLM can suggest an attractive number without understanding the system's true bottleneck. The current deterministic evaluator is also deliberately limited: it assumes a backend capacity of 50 requests per second. It is not a replay of recorded traffic. Until controlled load tests and meaningful user-level queue metrics are available, promotion must remain a deliberate human action.

The feature can demonstrate a complete governed suggestion workflow: observe counters, ask a local model for a candidate, reject unsafe values, inspect the proposal, run the existing capacity proxy, approve, and roll back. It cannot yet demonstrate continuous self-improvement. That requires storing past experiments, comparing candidate and baseline policies under equivalent traffic, and using measured outcomes to influence future proposals.

A useful next experiment is to run static limiting and waiting-room admission against identical k6 workloads while measuring backend errors, completed customer journeys, queue wait-time distributions, and fairness. A future evolution engine should learn from those outcomes, not merely from the number of 429 responses.
