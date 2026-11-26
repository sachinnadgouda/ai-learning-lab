
## Waiting-room fixes found during the pass-rate experiment ([PASS_RATE_RUNBOOK.md](PASS_RATE_RUNBOOK.md))
Load testing against the EVOLVED (queue) mode exposed three defects inherited from v0.1 that made the queue collapse under abandonment:
1. Tickets lived 900 s regardless of polling, so abandoned customers clogged the queue head. Tickets now expire if not polled for `DEMO_QUEUE_TICKET_TTL_SECONDS` (default 10); every poll refreshes the TTL.
2. Only the single head ticket could be admitted per call, capping throughput at the clients' poll cadence. Any ticket within the first `admissionPerSecond - alreadyAdmittedThisSecond` positions is now admitted (still FIFO-bounded).
3. Dead tickets inside that admission window are purged.
Covered by new `RedisAdaptersIT` cases.
