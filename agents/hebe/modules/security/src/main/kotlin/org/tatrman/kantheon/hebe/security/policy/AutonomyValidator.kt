package org.tatrman.kantheon.hebe.security.policy

import org.tatrman.kantheon.hebe.api.AutonomyLevel
import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ValidationResult
import org.tatrman.kantheon.hebe.api.Validator
import org.slf4j.LoggerFactory

class AutonomyValidator(
    private val level: AutonomyLevel,
) : Validator {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): ValidationResult =
        when (level) {
            AutonomyLevel.ReadOnly -> {
                if (tool.readOnly) ValidationResult.Allow else ValidationResult.Deny("ReadOnly mode blocks side-effect tools")
            }
            AutonomyLevel.Supervised -> {
                when {
                    tool.risk == RiskLevel.Low -> ValidationResult.Allow
                    else ->
                        ValidationResult.RequireApproval(
                            "Supervised mode requires approval for ${tool.risk.name} risk tool: ${tool.spec.name}",
                        )
                }
            }
            AutonomyLevel.Full -> {
                when (tool.risk) {
                    RiskLevel.Low -> ValidationResult.Allow
                    RiskLevel.Medium,
                    RiskLevel.High,
                    -> {
                        if (tool.effectiveRequiresApproval(call.args)) {
                            ValidationResult.RequireApproval(
                                "${tool.risk.name}-risk tool ${tool.spec.name} requires approval",
                            )
                        } else {
                            ValidationResult.Allow
                        }
                    }
                }
            }
            AutonomyLevel.YOLO -> {
                logger.warn("YOLO autonomy level active — all tools permitted")
                ValidationResult.Allow
            }
        }
}
