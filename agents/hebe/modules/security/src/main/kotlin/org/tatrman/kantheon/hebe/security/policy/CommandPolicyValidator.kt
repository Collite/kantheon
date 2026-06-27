package org.tatrman.kantheon.hebe.security.policy

import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ValidationResult
import org.tatrman.kantheon.hebe.api.Validator

class CommandPolicyValidator(
    private val allowedCommandGlobs: List<String>,
    private val forbiddenCommandGlobs: List<String>,
) : Validator {
    private val patternChecker = CommandPatternChecker()

    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): ValidationResult {
        val cmd = extractCmd(call.args) ?: return ValidationResult.Allow

        val normalized = normalizeGlobPatterns(allowedCommandGlobs)
        val forbiddenNormalized = normalizeGlobPatterns(forbiddenCommandGlobs)

        for (forbidden in forbiddenNormalized) {
            if (matchesGlob(cmd, forbidden)) {
                return ValidationResult.Deny("Command matches forbidden glob pattern: $forbidden")
            }
        }

        for (allowed in normalized) {
            if (matchesGlob(cmd, allowed)) {
                val patternResult = patternChecker.check(cmd)
                return when {
                    patternResult.severity == CommandPatternChecker.Severity.High -> {
                        ValidationResult.RequireApproval(
                            "Command passed allowlist but pattern checker flagged: ${patternResult.reason}",
                        )
                    }
                    else -> ValidationResult.Allow
                }
            }
        }

        return ValidationResult.Deny("Command not in allowlist")
    }

    private fun extractCmd(args: kotlinx.serialization.json.JsonObject): String? {
        val cmdElement = args["cmd"] ?: args["command"] ?: args["script"]
        return if (cmdElement is kotlinx.serialization.json.JsonPrimitive && cmdElement.isString) {
            cmdElement.content
        } else {
            null
        }
    }

    private fun normalizeGlobPatterns(patterns: List<String>): List<String> = patterns.map { it.trim() }

    private fun matchesGlob(
        cmd: String,
        glob: String,
    ): Boolean {
        val trimmedGlob = glob.trim()
        val trimmedCmd = cmd.trim()

        if (trimmedGlob == "*") return true

        if (!trimmedGlob.contains("*")) {
            return trimmedCmd.startsWith(trimmedGlob)
        }

        val regex = globToRegex(trimmedGlob)
        return regex.containsMatchIn(trimmedCmd)
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        sb.append(".*")
                        i += 2
                    } else {
                        sb.append(".*")
                        i++
                    }
                }
                '?' -> {
                    sb.append(".")
                    i++
                }
                '.' -> {
                    sb.append("\\.")
                    i++
                }
                else -> {
                    if (c in "\\+()[]{}^$|.{|}") {
                        sb.append("\\").append(c)
                    } else {
                        sb.append(c)
                    }
                    i++
                }
            }
        }
        sb.append("$")
        return Regex(sb.toString())
    }
}
