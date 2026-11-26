package dev.lab.auditor.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

@Serializable
enum class RuleType {
    FORBIDDEN_DEPENDENCY,
    ALLOWED_DEPENDENCIES_ONLY,
    NO_CYCLES,
    MAX_EFFERENT_COUPLING,
    MAX_INSTABILITY,
}

@Serializable
enum class Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
}

@Serializable
data class ArchitectureRule(
    val id: String,
    val name: String,
    val type: RuleType,
    val severity: Severity = Severity.HIGH,
    val sourcePattern: String? = null,
    val targetPattern: String? = null,
    val allowedTargets: List<String> = emptyList(),
    val granularity: CycleGranularity = CycleGranularity.MODULE,
    val maxThreshold: Double? = null,
    val description: String = "",
)

@Serializable
data class RuleSet(
    val version: String = "1.0",
    val name: String = "default",
    val rules: List<ArchitectureRule>,
)

@Serializable
data class ArchitectureViolation(
    val id: String,
    val ruleId: String,
    val ruleType: RuleType,
    val severity: Severity,
    val source: String,
    val target: String,
    val evidenceFile: String,
    val evidenceLine: Int,
    val message: String,
    val verified: Boolean = true,
) {
    init {
        require(verified) { "Deterministic ArchitectureViolation records must always have verified=true" }
    }
}

class RuleValidationException(val errors: List<String>) :
    IllegalArgumentException("Invalid architecture rule configuration: ${errors.joinToString("; ")}")

object PatternMatcher {
    private const val REGEX_PREFIX = "regex:"
    private const val GLOB_PREFIX = "glob:"

    fun compile(pattern: String): Regex {
        val trimmed = pattern.trim()
        require(trimmed.isNotEmpty()) { "Pattern must not be blank" }
        return when {
            trimmed.startsWith(REGEX_PREFIX) -> {
                val expr = trimmed.removePrefix(REGEX_PREFIX)
                require(expr.isNotBlank()) { "Regex pattern after 'regex:' must not be blank" }
                Regex(expr)
            }
            trimmed.startsWith(GLOB_PREFIX) -> {
                val expr = trimmed.removePrefix(GLOB_PREFIX)
                require(expr.isNotBlank()) { "Glob pattern after 'glob:' must not be blank" }
                globToRegex(expr)
            }
            else -> globToRegex(trimmed)
        }
    }

    fun matches(pattern: String, value: String): Boolean = compile(pattern).matches(value)

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val ch = glob[i]) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        sb.append(".*")
                        i++
                    } else {
                        sb.append("[^/]*")
                    }
                }
                '?' -> sb.append('.')
                '[', ']' -> throw IllegalArgumentException("Unsupported bracket expression in glob '$glob'; use 'regex:' prefix for character classes")
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '\\', '{', '}' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString())
    }
}

object RuleValidator {
    fun validate(ruleSet: RuleSet) {
        val errors = mutableListOf<String>()
        if (ruleSet.name.isBlank()) {
            errors.add("RuleSet name must not be blank")
        }
        val seenIds = mutableSetOf<String>()

        ruleSet.rules.forEachIndexed { idx, rule ->
            val prefix = "rule[$idx](${rule.id.ifBlank { "<blank>" }})"
            if (rule.id.isBlank()) {
                errors.add("$prefix: id must not be blank")
            } else if (!seenIds.add(rule.id)) {
                errors.add("$prefix: duplicate rule id '${rule.id}'")
            }
            if (rule.name.isBlank()) {
                errors.add("$prefix: name must not be blank")
            }

            validateOptionalPattern(rule.sourcePattern, "$prefix.sourcePattern", errors)
            validateOptionalPattern(rule.targetPattern, "$prefix.targetPattern", errors)
            rule.allowedTargets.forEachIndexed { tIdx, p ->
                validateRequiredPattern(p, "$prefix.allowedTargets[$tIdx]", errors)
            }

            when (rule.type) {
                RuleType.FORBIDDEN_DEPENDENCY -> {
                    if (rule.sourcePattern.isNullOrBlank()) {
                        errors.add("$prefix: FORBIDDEN_DEPENDENCY requires sourcePattern")
                    }
                    if (rule.targetPattern.isNullOrBlank()) {
                        errors.add("$prefix: FORBIDDEN_DEPENDENCY requires targetPattern")
                    }
                }
                RuleType.ALLOWED_DEPENDENCIES_ONLY -> {
                    if (rule.sourcePattern.isNullOrBlank()) {
                        errors.add("$prefix: ALLOWED_DEPENDENCIES_ONLY requires sourcePattern")
                    }
                }
                RuleType.NO_CYCLES -> Unit
                RuleType.MAX_EFFERENT_COUPLING -> {
                    val threshold = rule.maxThreshold
                    if (threshold == null || threshold < 0.0 || threshold.isNaN() || threshold.isInfinite()) {
                        errors.add("$prefix: MAX_EFFERENT_COUPLING requires non-negative maxThreshold")
                    }
                }
                RuleType.MAX_INSTABILITY -> {
                    val threshold = rule.maxThreshold
                    if (threshold == null || threshold < 0.0 || threshold > 1.0 || threshold.isNaN()) {
                        errors.add("$prefix: MAX_INSTABILITY requires maxThreshold in [0.0, 1.0]")
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw RuleValidationException(errors)
        }
    }

    private fun validateOptionalPattern(pattern: String?, field: String, errors: MutableList<String>) {
        if (pattern == null) return
        validateRequiredPattern(pattern, field, errors)
    }

    private fun validateRequiredPattern(pattern: String, field: String, errors: MutableList<String>) {
        if (pattern.isBlank()) {
            errors.add("$field must not be blank")
            return
        }
        try {
            PatternMatcher.compile(pattern)
        } catch (ex: Exception) {
            errors.add("$field invalid pattern '$pattern': ${ex.message}")
        }
    }
}

object RuleLoader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun defaultHexagonalRuleSet(): RuleSet = RuleSet(
        version = "1.0",
        name = "default-hexagonal",
        rules = listOf(
            ArchitectureRule(
                id = "domain-no-infra",
                name = "Domain must not depend on Infrastructure",
                type = RuleType.FORBIDDEN_DEPENDENCY,
                severity = Severity.CRITICAL,
                sourcePattern = "domain",
                targetPattern = "infrastructure",
                description = "Hexagonal core domain layer must remain independent of infrastructure adapters.",
            ),
            ArchitectureRule(
                id = "domain-no-api",
                name = "Domain must not depend on API/UI",
                type = RuleType.FORBIDDEN_DEPENDENCY,
                severity = Severity.CRITICAL,
                sourcePattern = "domain",
                targetPattern = "api",
                description = "Hexagonal core domain layer must not import inbound transport adapters.",
            ),
            ArchitectureRule(
                id = "no-module-cycles",
                name = "No circular module dependencies",
                type = RuleType.NO_CYCLES,
                severity = Severity.HIGH,
                granularity = CycleGranularity.MODULE,
                description = "Modules must form a Directed Acyclic Graph (DAG).",
            ),
            ArchitectureRule(
                id = "max-efferent-coupling",
                name = "Bounded module efferent coupling",
                type = RuleType.MAX_EFFERENT_COUPLING,
                severity = Severity.MEDIUM,
                maxThreshold = 4.0,
                description = "Flag modules depending on more than 4 other internal modules.",
            ),
            ArchitectureRule(
                id = "core-stability",
                name = "Core domain instability ceiling",
                type = RuleType.MAX_INSTABILITY,
                severity = Severity.HIGH,
                sourcePattern = "domain",
                maxThreshold = 0.25,
                description = "Core domain module must remain stable (I <= 0.25).",
            ),
        ),
    )

    fun loadFromFile(path: Path): RuleSet {
        require(Files.exists(path) && Files.isRegularFile(path)) {
            "Rules file does not exist: $path"
        }
        val content = Files.readString(path)
        val fileName = path.fileName.toString().lowercase()
        return if (fileName.endsWith(".json")) {
            parseJson(content)
        } else {
            parseYaml(content)
        }
    }

    fun parseJson(content: String): RuleSet {
        val parsed = try {
            json.decodeFromString(RuleSet.serializer(), content)
        } catch (ex: Exception) {
            throw RuleValidationException(listOf("Malformed JSON rules: ${ex.message}"))
        }
        RuleValidator.validate(parsed)
        return parsed
    }

    @Suppress("UNCHECKED_CAST")
    fun parseYaml(content: String): RuleSet {
        if (content.isBlank()) {
            throw RuleValidationException(listOf("YAML rules content must not be blank"))
        }
        val loaderOptions = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 20
        }
        val yaml = Yaml(SafeConstructor(loaderOptions))
        val rootObj = try {
            yaml.load<Any?>(content)
        } catch (ex: Exception) {
            throw RuleValidationException(listOf("Malformed YAML rules: ${ex.message}"))
        }
        val rootMap = rootObj as? Map<String, Any?>
            ?: throw RuleValidationException(listOf("YAML root must be a mapping object"))

        val version = rootMap["version"]?.toString() ?: "1.0"
        val name = rootMap["name"]?.toString() ?: "custom-yaml"
        val rawRules = rootMap["rules"] as? List<*>
            ?: throw RuleValidationException(listOf("YAML rules file must contain a 'rules' list"))

        val errors = mutableListOf<String>()
        val rules = mutableListOf<ArchitectureRule>()

        rawRules.forEachIndexed { idx, item ->
            val map = item as? Map<String, Any?>
            if (map == null) {
                errors.add("rules[$idx] must be a mapping")
                return@forEachIndexed
            }
            val id = map["id"]?.toString().orEmpty()
            val ruleName = map["name"]?.toString().orEmpty()
            val typeStr = map["type"]?.toString()?.uppercase().orEmpty()
            val type = runCatching { RuleType.valueOf(typeStr) }.getOrElse {
                errors.add("rules[$idx]($id): unknown rule type '$typeStr'")
                return@forEachIndexed
            }
            val severityStr = map["severity"]?.toString()?.uppercase() ?: "HIGH"
            val severity = runCatching { Severity.valueOf(severityStr) }.getOrElse {
                errors.add("rules[$idx]($id): unknown severity '$severityStr'")
                return@forEachIndexed
            }
            val granStr = map["granularity"]?.toString()?.uppercase() ?: "MODULE"
            val granularity = runCatching { CycleGranularity.valueOf(granStr) }.getOrElse {
                errors.add("rules[$idx]($id): unknown granularity '$granStr'")
                return@forEachIndexed
            }
            val maxThreshold = when (val raw = map["maxThreshold"] ?: map["max_threshold"]) {
                null -> null
                is Number -> raw.toDouble()
                else -> raw.toString().toDoubleOrNull().also {
                    if (it == null) errors.add("rules[$idx]($id): invalid numeric maxThreshold '$raw'")
                }
            }
            val allowedTargets = when (val raw = map["allowedTargets"] ?: map["allowed_targets"]) {
                null -> emptyList()
                is List<*> -> raw.map { it?.toString().orEmpty() }
                else -> {
                    errors.add("rules[$idx]($id): allowedTargets must be a list")
                    emptyList()
                }
            }

            rules.add(
                ArchitectureRule(
                    id = id,
                    name = ruleName,
                    type = type,
                    severity = severity,
                    sourcePattern = (map["sourcePattern"] ?: map["source_pattern"])?.toString(),
                    targetPattern = (map["targetPattern"] ?: map["target_pattern"])?.toString(),
                    allowedTargets = allowedTargets,
                    granularity = granularity,
                    maxThreshold = maxThreshold,
                    description = map["description"]?.toString().orEmpty(),
                ),
            )
        }

        if (errors.isNotEmpty()) {
            throw RuleValidationException(errors)
        }
        val ruleSet = RuleSet(version = version, name = name, rules = rules)
        RuleValidator.validate(ruleSet)
        return ruleSet
    }
}
