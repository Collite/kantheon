package org.tatrman.kantheon.hebe.security.policy

object SecretPatterns {
    data class Pattern(
        val name: String,
        val regex: Regex,
        val severity: Severity,
    )

    enum class Severity { Low, Medium, High }

    val defaultPatterns =
        listOf(
            Pattern(
                name = "aws_access_key",
                regex = Regex("AKIA[0-9A-Z]{16}"),
                severity = Severity.High,
            ),
            Pattern(
                name = "aws_secret",
                regex = Regex("(?i)aws_secret_access_key.*[0-9a-z+/]{40}"),
                severity = Severity.High,
            ),
            Pattern(
                name = "openai",
                regex = Regex("sk-[A-Za-z0-9]{20,}"),
                severity = Severity.High,
            ),
            Pattern(
                name = "anthropic",
                regex = Regex("sk-ant-[A-Za-z0-9-_]{20,}"),
                severity = Severity.High,
            ),
            Pattern(
                name = "github_pat",
                regex = Regex("ghp_[A-Za-z0-9]{36}"),
                severity = Severity.High,
            ),
            Pattern(
                name = "github_pat_v2",
                regex = Regex("github_pat_[A-Za-z0-9_]{82,}"),
                severity = Severity.High,
            ),
            Pattern(
                name = "stripe",
                regex = Regex("sk_live_[A-Za-z0-9]{24}"),
                severity = Severity.High,
            ),
            Pattern(
                name = "slack",
                regex = Regex("xox[baprs]-[A-Za-z0-9-]{10,}"),
                severity = Severity.High,
            ),
            Pattern(
                name = "high_entropy_token",
                regex = Regex("[A-Za-z0-9_-]{32,}"),
                severity = Severity.Medium,
            ),
        )

    private val whitelistedPatterns =
        listOf(
            Regex("[0-9a-f]{40}"),
            Regex("[0-9a-f]{32}"),
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
        )

    fun isWhitelisted(value: String): Boolean = whitelistedPatterns.any { it.matches(value) }

    fun hasHighEntropy(value: String): Boolean {
        if (value.length < 32) return false
        if (isWhitelisted(value)) return false

        val charFrequencies = value.groupingBy { it }.eachCount()
        val entropy =
            charFrequencies.values.sumOf { freq ->
                val p = freq.toDouble() / value.length
                -p * (kotlin.math.ln(p) / kotlin.math.ln(2.0))
            }
        return entropy > 4.5
    }
}
