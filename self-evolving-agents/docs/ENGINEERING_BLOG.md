# When Traffic Spikes, Should the System Change Its Own Rules?

*Building a governed traffic-control experiment with Spring Boot, Redis, and a local feedback loop*

A flash sale is a useful way to expose an uncomfortable truth about backend systems: the number of requests a service can accept is not the same as the number it can finish. The gap often appears suddenly. A promotion goes live, thousands of customers refresh the same page, and the database starts spending more time managing contention than doing useful work.

The familiar response is to add a rate limiter. That protects the service, but it can also turn an overload incident into a wall of rejected customers. A virtual waiting room gives us another option: hold arrivals outside the expensive path, admit them gradually, and preserve a visible place in line.

The interesting question is not whether we can build those two mechanisms. We can. The interesting question is whether the system can improve *how it uses them* after seeing the outcome of an incident — without giving an AI model permission to improvise on a live production gateway.

This repository explores that question with a deliberately constrained local experiment.

## A policy, not a prompt, controls admission

The request path is simple. A Spring Boot endpoint asks Redis whether a request can enter. A small Lua script makes the decision atomically: admit now, reject under the static policy, or issue a waiting-room ticket when the evolved policy is active. Redis is the shared coordination point, so concurrent application requests do not each make their own independent admission decision.

A separate policy service manages a few explicit parameters: the operating mode, the admission budget, the queue bound, and the amount of simulated backend work. Those values are versioned. The request handler never asks a language model what to do for an individual shopper.

That separation matters. An admission decision has to be fast, deterministic, and available during an incident. Model inference is not a suitable dependency on the hot path.

## The first experiment: a deliberately imperfect policy

We start with a static policy that admits up to 60 requests per second and rejects requests beyond that budget. We then generate a rising workload with k6. The objective is not to claim that 60 requests/sec is universally safe; it is to make the operating rule visible and testable.

The proposal mechanism creates a second policy. It lowers the admission budget to 48 requests/sec and enables a bounded FIFO waiting room. Instead of rejecting every excess arrival, the application returns a ticket and lets the client poll until admission is available.

This is a meaningful behavior change, but it is not yet proof of intelligence. A hand-written rule can make exactly the same change. That is why the demo calls its proposal engine *deterministic* rather than pretending a local model has learned a strategy.

## Why evaluation comes before activation

The candidate goes through a gate. The current implementation checks a declared capacity proxy: assume the protected backend can handle 50 admitted requests/sec and compare how much each policy exceeds that limit. Under this assumption, a 48 requests/sec candidate is safer than a 60 requests/sec baseline.

It is a useful mechanical check, but it does not answer the harder questions. Did p95 latency improve? Were customers waiting longer than expected? Did the queue starve newer users? Did throughput fall unnecessarily? A proper evaluator must replay the same workload, gather real measurements, and reject a candidate that merely moves the pain somewhere else.

For this reason, the approval step is explicit. The system does not silently promote a proposed policy. A human reviews the candidate and its evidence, activates it, and can roll back to the previous version.

## What would make it genuinely self-evolving?

The next iteration would preserve incident traces, generate several bounded candidate policies, and test them offline. A model running locally through Ollama could suggest hypotheses — for example, reducing admission batch sizes when queue releases cause latency spikes — but the model would not score its own homework.

An evaluator would measure throughput, tail latency, 5xx responses, rejection rates, queue wait times, and fairness under identical workloads. A governance layer would enforce hard constraints and require approval before promotion. The winning policy, the evidence that supported it, and any observed regressions would become durable knowledge for future incidents.

That is the difference between *adaptive control* and *governed self-evolution*. Adaptive control changes parameters when metrics cross thresholds. Governed evolution changes the policy-selection strategy based on tested experience, retains successful changes, and can explain why an earlier approach was replaced.

## The trade-off we should not hide

A waiting room protects a backend partly by shifting latency from inside the service to the user's experience. It can improve error rates while making customers wait longer. An experiment that reports only backend p95 latency would miss that trade-off entirely.

Fairness is another challenge. An opaque ticket and FIFO list are a reasonable starting point for a local demo, not a finished queueing product. Production systems need identity binding, cancellation, expiration cleanup, abuse prevention, durable queue semantics, and clear user messaging.

The same caution applies to the AI layer. If a model is allowed to rewrite production routing logic without guardrails, an experiment can become an outage. Safer evolution changes typed, bounded configuration in a sandbox, with independently measured results and a reversible release path.

## Where this leaves us

The first version demonstrates the skeleton: traffic arrives, a policy decides admission, a candidate policy is proposed, a check runs, an operator approves it, and rollback remains available. Everything runs locally through Docker Compose. No cloud services and no paid AI API are required.

It is not a claim that the application has discovered a superior rate-limiting algorithm. It is a practical foundation for testing that claim rigorously. The next milestone is empirical replay and a local model that proposes candidate changes while deterministic validators remain in charge of promotion.

The engineering principle is straightforward: **let the agent propose; let measurements and policy decide.**

---

*Conceptual inspiration: Google's article on governed self-evolving agents and the broader ideas of experimentation, causal evaluation, and controlled promotion. This article is original and describes this repository's own architecture, behavior, and limitations.*
