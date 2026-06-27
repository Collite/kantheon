package org.tatrman.kantheon.hebe.security.policy

import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ValidationResult
import org.tatrman.kantheon.hebe.api.Validator
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneResult
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneScanner
import org.tatrman.kantheon.hebe.memory.hygiene.Severity

class PromptInjectionValidator : Validator {
    private val scanner = HygieneScanner(HygieneScanner.defaultRules())
    private val turnVerdicts = mutableMapOf<String, HygieneResult>()

    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): ValidationResult {
        val cachedResult = turnVerdicts[ctx.turnId]
        if (cachedResult != null) {
            return resultToValidation(cachedResult)
        }

        val contentToScan =
            buildString {
                for ((_, value) in call.args) {
                    if (value is kotlinx.serialization.json.JsonPrimitive && value.isString) {
                        appendLine(value.content)
                    }
                }
            }

        val result = scanner.scan(contentToScan)
        turnVerdicts[ctx.turnId] = result

        return resultToValidation(result)
    }

    private fun resultToValidation(result: HygieneResult): ValidationResult =
        when (result) {
            is HygieneResult.Clean -> ValidationResult.Allow
            is HygieneResult.Warn -> {
                if (result.findings.any { it.severity == Severity.High }) {
                    ValidationResult.Deny("Prompt injection patterns detected: ${summarize(result)}")
                } else {
                    ValidationResult.RequireApproval("Prompt injection patterns detected: ${summarize(result)}")
                }
            }
            is HygieneResult.Reject -> {
                ValidationResult.Deny("Prompt injection blocked: ${summarize(result)}")
            }
        }

    private fun summarize(result: HygieneResult): String {
        val findings =
            when (result) {
                is HygieneResult.Clean -> return ""
                is HygieneResult.Warn -> result.findings
                is HygieneResult.Reject -> result.findings
            }
        return findings.joinToString("; ") { it.rule }
    }

    fun clearTurnCache(turnId: String) {
        turnVerdicts.remove(turnId)
    }

    fun clearAllCache() {
        turnVerdicts.clear()
    }
}
