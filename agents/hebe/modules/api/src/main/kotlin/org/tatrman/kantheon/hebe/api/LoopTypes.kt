package org.tatrman.kantheon.hebe.api

import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

sealed interface LoopSignal {
    data object Continue : LoopSignal

    data object Estop : LoopSignal

    data object Cancel : LoopSignal
}

sealed interface LoopOutcome {
    @Serializable
    @SerialName("response")
    data class Response(
        val text: String,
    ) : LoopOutcome

    @Serializable
    @SerialName("stopped")
    data object Stopped : LoopOutcome

    @Serializable
    @SerialName("max_iterations")
    data object MaxIterations : LoopOutcome

    @Serializable
    @SerialName("failure")
    data class Failure(
        val message: String,
    ) : LoopOutcome

    @Serializable
    @SerialName("need_approval")
    data class NeedApproval(
        val request: ApprovalRequest,
    ) : LoopOutcome

    @Serializable
    @SerialName("auth_pending")
    data class AuthPending(
        val purpose: String,
    ) : LoopOutcome
}

sealed interface RespondOutput {
    @Serializable
    @SerialName("text_only")
    data class TextOnly(
        val text: String,
    ) : RespondOutput

    @Serializable
    @SerialName("with_tool_calls")
    data class WithToolCalls(
        val calls: List<ParsedToolCall>,
    ) : RespondOutput
}

enum class TextAction {
    FinishWith,
    ContinueLoop,
}

data class LoopConfig(
    val maxIterations: Int = 20,
    val costBudget: Double = 5.0,
    val compactionThreshold: Double = 0.6,
)

interface ReasoningContext {
    val sessionId: String
    val turnId: String
    val userId: String
    val requestor: Channel
    val workspace: WorkspacePath
    val approvalGate: ApprovalGate
    val observer: Observer
    val secretLookup: SecretLookup
}

interface Reasoning {
    val systemPrompt: String
    val activeSkills: List<String>
    val latestUserMessage: String
}
