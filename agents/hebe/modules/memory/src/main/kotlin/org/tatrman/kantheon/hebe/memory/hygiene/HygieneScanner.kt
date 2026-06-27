package org.tatrman.kantheon.hebe.memory.hygiene

sealed interface HygieneResult {
    data object Clean : HygieneResult

    data class Warn(
        val findings: List<Finding>,
    ) : HygieneResult

    data class Reject(
        val findings: List<Finding>,
    ) : HygieneResult
}

data class Finding(
    val rule: String,
    val severity: Severity,
    val excerpt: String,
)

enum class Severity {
    Low,
    Medium,
    High,
}

class HygieneScanner(
    private val rules: List<HygieneRule> = defaultRules(),
) {
    fun scan(content: String): HygieneResult {
        val findings = mutableListOf<Finding>()
        for (rule in rules) {
            rule.pattern.findAll(content).forEach { match ->
                findings.add(Finding(rule.name, rule.severity, match.value))
            }
        }
        if (findings.isEmpty()) return HygieneResult.Clean
        val hasReject = findings.any { it.severity == Severity.High }
        return if (hasReject) HygieneResult.Reject(findings) else HygieneResult.Warn(findings)
    }

    companion object {
        fun defaultRules(): List<HygieneRule> =
            listOf(
                HygieneRule(
                    name = "prompt_injection_ignore",
                    severity = Severity.High,
                    pattern =
                        Regex(
                            "\\b(ignore|disregard).{0,30}(previous|above)\\s+(instructions|prompts|rules)",
                            RegexOption.IGNORE_CASE,
                        ),
                ),
                HygieneRule(
                    name = "pretend_admin",
                    severity = Severity.High,
                    pattern =
                        Regex(
                            "\\bpretend\\s+(you|to\\s+be)\\b.+\\b(developer|admin|root)\\b",
                            RegexOption.IGNORE_CASE,
                        ),
                ),
                HygieneRule(
                    name = "html_system_tag",
                    severity = Severity.Medium,
                    pattern = Regex("<\\s*system\\s*>", RegexOption.IGNORE_CASE),
                ),
                HygieneRule(
                    name = "curl_pipe_bash",
                    severity = Severity.Medium,
                    pattern = Regex("\\bcurl\\s+.+\\|\\s*(bash|sh)\\b", RegexOption.IGNORE_CASE),
                ),
                HygieneRule(
                    name = "exec_rm_sudo",
                    severity = Severity.Medium,
                    pattern = Regex("\\bexec\\s*\\(\\s*['\"]?(rm|sudo)\\b", RegexOption.IGNORE_CASE),
                ),
                HygieneRule(
                    name = "prompt_marker",
                    severity = Severity.Low,
                    pattern = Regex("###\\s+(ASSISTANT|SYSTEM):", RegexOption.IGNORE_CASE),
                ),
            )
    }
}

data class HygieneRule(
    val name: String,
    val severity: Severity,
    val pattern: Regex,
)
