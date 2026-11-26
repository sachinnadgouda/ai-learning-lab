# When a Flash Sale Teaches the Traffic Controller New Rules

A sudden flash sale is a good way to expose the limits of static traffic controls. The application may be healthy at a few dozen requests per second, then abruptly spend most of its time refusing traffic. Adding a waiting room helps, but introduces new questions: how quickly should users be admitted, how large should the queue become, and when should the system change strategy?

We built a local Spring Boot experiment to explore a more ambitious answer: a traffic controller that can propose changes to its own operating policy, test them, and retain the evidence. The system runs with Redis, k6, Prometheus, Grafana and an Ollama model in Docker. There is no hosted inference service.

## A policy is a hypothesis

A traffic policy has a mode, an admission budget, and a maximum queue length. Those numbers are not universally correct. They depend on backend capacity and the arrival pattern. The first version uses a static admission budget of 60 requests per second. Under heavier demand, it returns 429 responses rather than letting every request reach the backend.

The waiting room changes the tradeoff. Instead of refusing every excess arrival, it can issue a ticket and admit queued users when capacity becomes available. That protects the backend, but a queue is not free: users can abandon it, tickets can expire, and waiting time can become unacceptable.

## The evolution loop

The application counts new arrivals in Redis in one-second buckets. An evolution coordinator can periodically detect sustained traffic, ask a local Ollama model for a constrained policy candidate, and evaluate the candidate using a separate deterministic replay. Both the current policy and candidate receive the same observed arrival series. The replay estimates completions, rejections, queue backlog, wait time, and admissions beyond an assumed backend capacity.

The result is stored in Redis. Later proposals include a small selection of prior experiments, so the model has some evidence about what happened before. This is operational memory, not retraining. The model's output is never accepted as a fact: it is parsed, bounded and checked before it can become a candidate.

## Governance is part of the feature

There are two switches. One enables AI proposals; the other enables scheduled proposal cycles. Even with both switches on, no proposal is automatically promoted. An operator must inspect the candidate and approve it. A rollback endpoint restores the previous policy.

This separation matters. A language model is useful for exploring possible strategies, but it is not a safety controller. The active policy must remain usable if Ollama is slow, unavailable, or simply wrong.

## What this experiment does not prove

A replay of arrivals is not the same as replaying real HTTP requests. It cannot establish actual database contention, p95 latency, client fairness, or user conversion. We therefore treat the replay as a screening tool. A serious evaluation must also run repeatable k6 scenarios with isolated state and compare measured outcomes against a deterministic adaptive baseline.

The prototype is deliberately conservative: it may reject proposals that could be useful. That is preferable to claiming improvement based on model confidence alone. The interesting question is not whether the AI can change a number. It is whether the system can accumulate evidence that helps it make better, safer decisions across repeated incidents.

## Where to take it next

The next iteration should introduce a true experimental harness that provisions isolated instances for candidate and baseline policies, measures real p95 and 5xx rates, and ties each approval to a signed experiment report. Only after that should automatic promotion within a narrow preapproved envelope be considered.

This is a small local interpretation of governed agent evolution concepts, not a reproduction of any vendor platform or architecture.
