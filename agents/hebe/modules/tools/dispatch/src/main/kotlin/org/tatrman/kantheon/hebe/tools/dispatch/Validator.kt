package org.tatrman.kantheon.hebe.tools.dispatch

import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ValidationResult
import org.tatrman.kantheon.hebe.api.Validator

sealed interface DispatchValidationResult {
    data object Allow : DispatchValidationResult

    data class RequireApproval(
        val prompt: String,
    ) : DispatchValidationResult

    data class Deny(
        val reason: String,
    ) : DispatchValidationResult
}

interface DispatchValidator {
    suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): DispatchValidationResult
}

fun Validator.toDispatchValidator(): DispatchValidator = DispatchValidatorImpl(this)

private class DispatchValidatorImpl(
    val validator: Validator,
) : DispatchValidator {
    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): DispatchValidationResult =
        when (val result = validator.validate(call, tool, ctx)) {
            is ValidationResult.Allow -> DispatchValidationResult.Allow
            is ValidationResult.RequireApproval -> DispatchValidationResult.RequireApproval(result.prompt)
            is ValidationResult.Deny -> DispatchValidationResult.Deny(result.reason)
        }
}
