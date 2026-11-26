package dev.lab.auditor.domain

import kotlinx.serialization.Serializable

@Serializable
data class InjectionDetection(
    val patternName: String,
    val matchedFragment: String,
)

@Serializable
data class SanitizationResult(
    val sanitizedText: String,
    val detections: List<InjectionDetection>,
) {
    val hadInjectionAttempt: Boolean get() = detections.isNotEmpty()
}

/**
 * Treats all scanned source code, comments, file paths, and symbol names as untrusted input.
 * Neutralizes prompt-injection payloads and fences untrusted text before building AI prompts.
 */
object PromptInjectionSanitizer {

    private data class PatternRule(val name: String, val regex: Regex)

    private val injectionPatterns: List<PatternRule> = listOf(
        PatternRule(
            "ignore-previous-instructions",
            Regex("(?i)\\b(ignore|disregard|forget|override)\\b.{0,40}\\b(previous|above|prior|all|system)\\b.{0,30}\\b(instructions?|rules?|prompts?|policies?)\\b"),
        ),
        PatternRule(
            "suppress-violations",
            Regex("(?i)\\b(suppress|delete|remove|hide|drop|ignore|clear|falsify)\\b.{0,35}\\b(violations?|findings?|cycles?|errors?|alerts?)\\b"),
        ),
        PatternRule(
            "verified-flag-tampering",
            Regex("(?i)(verified\\s*[:=]\\s*false|mark\\s+as\\s+(clean|safe|passed|no\\s+violations?)|return\\s+empty\\s+(array|list|json))"),
        ),
        PatternRule(
            "role-spoofing-delimiter",
            Regex("(?i)(<\\|im_start\\|>|<\\|im_end\\|>|<\\|system\\|>|```\\s*system|^\\s*(system|assistant|developer)\\s*:)"),
        ),
        PatternRule(
            "new-system-prompt",
            Regex("(?i)\\b(you\\s+are\\s+now|act\\s+as\\s+(a\\s+)?(system|admin|auditor\\s+override)|new\\s+system\\s+prompt)\\b"),
        ),
        PatternRule(
            "fence-escape",
            Regex("<<<\\s*/?\\s*UNTRUSTED_SOURCE_DATA[^>]*>>>", RegexOption.IGNORE_CASE),
        ),
    )

    fun sanitize(input: String, maxChars: Int = 2000): SanitizationResult {
        val truncated = if (input.length > maxChars) input.take(maxChars) + "…[truncated]" else input
        val detections = mutableListOf<InjectionDetection>()
        var current = truncated

        // Strip ASCII control characters except newline/tab
        current = current.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")

        for (rule in injectionPatterns) {
            val matches = rule.regex.findAll(current).toList()
            if (matches.isNotEmpty()) {
                for (match in matches) {
                    detections.add(
                        InjectionDetection(
                            patternName = rule.name,
                            matchedFragment = match.value.take(120),
                        ),
                    )
                }
                current = rule.regex.replace(current, "[REDACTED_PROMPT_INJECTION:${rule.name}]")
            }
        }

        return SanitizationResult(
            sanitizedText = current,
            detections = detections,
        )
    }

    fun fenceUntrustedData(label: String, rawContent: String, maxChars: Int = 2000): Pair<String, List<InjectionDetection>> {
        val cleanLabel = label.replace(Regex("[^A-Za-z0-9_.:/-]"), "_")
        val result = sanitize(rawContent, maxChars)
        val fenced = buildString {
            append("<<<UNTRUSTED_SOURCE_DATA:$cleanLabel>>>\n")
            append(result.sanitizedText)
            append("\n<<<END_UNTRUSTED_SOURCE_DATA:$cleanLabel>>>")
        }
        return fenced to result.detections
    }
}
