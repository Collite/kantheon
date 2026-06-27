package org.tatrman.kantheon.hebe.api

sealed interface ValidationResult {
    data object Allow : ValidationResult

    data class RequireApproval(
        val prompt: String,
    ) : ValidationResult

    data class Deny(
        val reason: String,
    ) : ValidationResult
}

interface Validator {
    suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): ValidationResult
}

interface LeakDetector {
    fun scan(result: ToolResult): ToolResult
}
