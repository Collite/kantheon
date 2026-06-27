package org.tatrman.kantheon.hebe.security.policy

class CommandPatternChecker {
    private val dangerousPatterns =
        listOf(
            CommandSubstitutionChecker,
            PipeToShellChecker,
            NetworkExfilChecker,
            VariableSubstitutionChecker,
            RmRfChecker,
        )

    fun check(cmd: String): PatternCheckResult {
        for (checker in dangerousPatterns) {
            val result = checker.matches(cmd)
            if (result != null) {
                return PatternCheckResult(result.first, result.second)
            }
        }
        return PatternCheckResult(Severity.Allowed, "")
    }

    data class PatternCheckResult(
        val severity: Severity,
        val reason: String,
    )

    enum class Severity { Allowed, Low, Medium, High }

    interface DangerousChecker {
        fun matches(cmd: String): Pair<Severity, String>?
    }

    object CommandSubstitutionChecker : DangerousChecker {
        private val backtickPattern = Regex("`[^`]+`")
        private val dollarParenPattern = Regex("\\$\\([^)]+\\)")
        private val harmfulKeywords = listOf("curl", "wget", "sh", "bash")

        override fun matches(cmd: String): Pair<Severity, String>? {
            val containsBacktick = backtickPattern.containsMatchIn(cmd)
            val containsDollarParen = dollarParenPattern.containsMatchIn(cmd)

            if (!containsBacktick && !containsDollarParen) return null

            for (keyword in harmfulKeywords) {
                if (cmd.contains(keyword)) return Pair(Severity.High, "Command substitution with dangerous keyword detected")
            }
            return Pair(Severity.High, "Command substitution detected")
        }
    }

    object PipeToShellChecker : DangerousChecker {
        private val pipeToShellPattern = Regex("\\|\\s*(bash|sh|python|ruby|perl|lua|php|node)")

        override fun matches(cmd: String): Pair<Severity, String>? =
            if (pipeToShellPattern.containsMatchIn(cmd)) {
                Pair(Severity.High, "Pipe to shell detected")
            } else {
                null
            }
    }

    object NetworkExfilChecker : DangerousChecker {
        private val exfilPatterns =
            listOf(
                Regex("cat\\s+/etc/passwd\\s*\\|"),
                Regex("cat\\s+/etc/shadow\\s*\\|"),
                Regex("env\\s*\\|"),
                Regex("printenv\\s*\\|"),
            )

        override fun matches(cmd: String): Pair<Severity, String>? =
            exfilPatterns.firstOrNull { it.containsMatchIn(cmd) }?.let {
                Pair(Severity.High, "Potential network exfiltration detected")
            }
    }

    object VariableSubstitutionChecker : DangerousChecker {
        private val ifsPattern = Regex(String(charArrayOf('$', 'I', 'F', 'S')))
        private val dollarNine = String(charArrayOf('$', '9'))

        override fun matches(cmd: String): Pair<Severity, String>? {
            if (ifsPattern.containsMatchIn(cmd) && cmd.contains(dollarNine)) {
                return Pair(Severity.Medium, "Variable substitution tricks detected")
            }
            if (ifsPattern.containsMatchIn(cmd)) {
                return Pair(Severity.Medium, "Variable substitution tricks detected")
            }
            return null
        }
    }

    object RmRfChecker : DangerousChecker {
        private val rmRfPattern = Regex("rm\\s+(-[rf]+\\s+)*(/|\\*)")

        override fun matches(cmd: String): Pair<Severity, String>? =
            if (rmRfPattern.containsMatchIn(cmd)) {
                Pair(Severity.High, "Dangerous rm -rf pattern detected")
            } else {
                null
            }
    }
}
