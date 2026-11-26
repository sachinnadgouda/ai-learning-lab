package dev.lab.auditor.infrastructure

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object SecretRedactor {
    private val secretPattern = Regex(
        "(?i)(bearer\\s+[A-Za-z0-9._\\-]{8,}|(?:api[_-]?key|token|secret|password|authorization)\\s*[:=]\\s*['\"]?[A-Za-z0-9._\\-]{6,}['\"]?)",
    )

    fun redact(text: String): String = secretPattern.replace(text, "[REDACTED_SECRET]")
}

class AuditorMetricsRegistry {
    private val httpRequests = ConcurrentHashMap<String, AtomicLong>()
    private val httpDurationMsTotal = AtomicLong(0)
    private val scansTotal = AtomicLong(0)
    private val violationsTotal = AtomicLong(0)
    private val cyclesTotal = AtomicLong(0)
    private val cacheHitsTotal = AtomicLong(0)
    private val cacheMissesTotal = AtomicLong(0)
    private val aiCallsTotal = ConcurrentHashMap<String, AtomicLong>()
    private val aiLatencyMsTotal = AtomicLong(0)
    private val aiInjectionNeutralizedTotal = AtomicLong(0)

    fun recordHttpRequest(method: String, route: String, status: Int, durationMs: Long) {
        val key = "$method|$route|$status"
        httpRequests.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        httpDurationMsTotal.addAndGet(durationMs.coerceAtLeast(0))
    }

    fun recordScan(violations: Int, cycles: Int, cacheHits: Int, cacheMisses: Int) {
        scansTotal.incrementAndGet()
        violationsTotal.addAndGet(violations.toLong())
        cyclesTotal.addAndGet(cycles.toLong())
        cacheHitsTotal.addAndGet(cacheHits.toLong())
        cacheMissesTotal.addAndGet(cacheMisses.toLong())
    }

    fun recordAiInvocation(status: String, latencyMs: Long, injectionsNeutralized: Int) {
        aiCallsTotal.computeIfAbsent(status) { AtomicLong(0) }.incrementAndGet()
        aiLatencyMsTotal.addAndGet(latencyMs.coerceAtLeast(0))
        aiInjectionNeutralizedTotal.addAndGet(injectionsNeutralized.toLong())
    }

    fun renderPrometheus(): String = buildString {
        appendLine("# HELP auditor_scans_total Total completed deterministic architecture scans")
        appendLine("# TYPE auditor_scans_total counter")
        appendLine("auditor_scans_total ${scansTotal.get()}")
        appendLine("# HELP auditor_verified_violations_total Cumulative verified architectural violations detected")
        appendLine("# TYPE auditor_verified_violations_total counter")
        appendLine("auditor_verified_violations_total ${violationsTotal.get()}")
        appendLine("# HELP auditor_cycles_total Cumulative circular dependency cycles detected")
        appendLine("# TYPE auditor_cycles_total counter")
        appendLine("auditor_cycles_total ${cyclesTotal.get()}")
        appendLine("# HELP auditor_file_cache_hits_total Incremental file parse cache hits")
        appendLine("# TYPE auditor_file_cache_hits_total counter")
        appendLine("auditor_file_cache_hits_total ${cacheHitsTotal.get()}")
        appendLine("# HELP auditor_file_cache_misses_total Incremental file parse cache misses")
        appendLine("# TYPE auditor_file_cache_misses_total counter")
        appendLine("auditor_file_cache_misses_total ${cacheMissesTotal.get()}")
        appendLine("# HELP auditor_ai_latency_ms_total Cumulative AI remediation call latency in milliseconds")
        appendLine("# TYPE auditor_ai_latency_ms_total counter")
        appendLine("auditor_ai_latency_ms_total ${aiLatencyMsTotal.get()}")
        appendLine("# HELP auditor_ai_prompt_injections_neutralized_total Prompt injection payloads neutralized before LLM calls")
        appendLine("# TYPE auditor_ai_prompt_injections_neutralized_total counter")
        appendLine("auditor_ai_prompt_injections_neutralized_total ${aiInjectionNeutralizedTotal.get()}")
        appendLine("# HELP auditor_ai_invocations_total AI advisor invocations by status")
        appendLine("# TYPE auditor_ai_invocations_total counter")
        aiCallsTotal.entries.sortedBy { it.key }.forEach { (status, count) ->
            appendLine("auditor_ai_invocations_total{status=\"$status\"} ${count.get()}")
        }
        appendLine("# HELP auditor_http_requests_total HTTP API requests by method, path, and status")
        appendLine("# TYPE auditor_http_requests_total counter")
        httpRequests.entries.sortedBy { it.key }.forEach { (key, count) ->
            val parts = key.split('|')
            appendLine("auditor_http_requests_total{method=\"${parts[0]}\",path=\"${parts[1]}\",status=\"${parts[2]}\"} ${count.get()}")
        }
        appendLine("# HELP auditor_http_request_duration_ms_total Cumulative HTTP API latency in milliseconds")
        appendLine("# TYPE auditor_http_request_duration_ms_total counter")
        appendLine("auditor_http_request_duration_ms_total ${httpDurationMsTotal.get()}")
    }
}
