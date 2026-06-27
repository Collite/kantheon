package org.tatrman.kantheon.hebe.core.delegate

import org.tatrman.kantheon.hebe.api.Channel
import org.tatrman.kantheon.hebe.api.ChatMessage
import org.tatrman.kantheon.hebe.api.ChatRequest
import org.tatrman.kantheon.hebe.api.ChatRole
import org.tatrman.kantheon.hebe.api.ConversationMessage
import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.api.LoopConfig
import org.tatrman.kantheon.hebe.api.LoopOutcome
import org.tatrman.kantheon.hebe.api.LoopSignal
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.Reasoning
import org.tatrman.kantheon.hebe.api.ReasoningContext
import org.tatrman.kantheon.hebe.api.RespondOutput
import org.tatrman.kantheon.hebe.api.SecretLookup
import org.tatrman.kantheon.hebe.api.StreamEvent
import org.tatrman.kantheon.hebe.api.TextAction
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.core.compaction.PreemptivePruner
import org.tatrman.kantheon.hebe.core.cost.CostGuard
import org.tatrman.kantheon.hebe.core.loop.LoopDelegate
import org.tatrman.kantheon.hebe.core.loop.runAgenticLoop
import org.tatrman.kantheon.hebe.providers.openai.withTurnRef
import org.tatrman.kantheon.hebe.tools.dispatch.DispatchOutcome
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

class JobDelegate(
    private val jobId: String,
    private val memory: MemoryStore,
    private val dispatcher: ToolDispatcher,
    private val llmProvider: LlmProvider,
    private val costGuard: CostGuard,
    private val compactor: PreemptivePruner,
    private val observer: Observer,
    private val systemPrompt: String,
    private val tools: List<org.tatrman.kantheon.hebe.api.ToolSpec>,
    private val modelName: String,
) : LoopDelegate {
    private val logger = LoggerFactory.getLogger(javaClass)

    private var estop: Boolean = false
    private var cancel: Boolean = false

    private var compactedHistory: List<ConversationMessage>? = null
    private var lastTokensIn: Int = 0
    private var lastTokensOut: Int = 0
    private var lastTokensCached: Int = 0
    private var lastCostMicrosUsd: Long? = null
    private var lastCallStartMs: Long = 0
    private var lastTurnId: String = ""

    override suspend fun checkSignals(): LoopSignal {
        if (estop) return LoopSignal.Estop
        if (cancel) return LoopSignal.Cancel
        return LoopSignal.Continue
    }

    override suspend fun beforeLlmCall(
        ctx: ReasoningContext,
        iter: Int,
    ): LoopOutcome? {
        val costCheck = costGuard.checkAllowed(ctx.turnId)
        return when (costCheck) {
            is CostGuard.CheckResult.DenyDaily -> LoopOutcome.Failure("daily budget exceeded: \$${costCheck.spentUsd}")
            is CostGuard.CheckResult.DenyPerTurn -> LoopOutcome.Failure("per-turn token cap exceeded")
            CostGuard.CheckResult.Allow -> {
                val history = memory.loadContext(jobId)
                val pruneResult = compactor.prune(history, ctx.turnId)
                if (pruneResult.compacted) {
                    logger.debug("job={} compaction ran at iter={}", jobId, iter)
                    compactedHistory = pruneResult.messages
                }
                null
            }
        }
    }

    override suspend fun callLlm(
        reasoning: Reasoning,
        ctx: ReasoningContext,
    ): RespondOutput {
        lastCallStartMs = System.currentTimeMillis()
        lastTurnId = ctx.turnId

        val history = compactedHistory ?: memory.loadContext(jobId)
        compactedHistory = null

        val chatMessages =
            history.map { convMsg ->
                when (convMsg.role) {
                    ChatRole.User -> ChatMessage.User(convMsg.content)
                    ChatRole.Assistant -> ChatMessage.Assistant(convMsg.content, convMsg.toolCalls)
                    ChatRole.Tool ->
                        ChatMessage.ToolResult(
                            convMsg.toolCalls.firstOrNull()?.id ?: "",
                            convMsg.content,
                        )
                    ChatRole.System -> ChatMessage.System(convMsg.content)
                }
            }
        val request =
            ChatRequest(
                model = modelName,
                systemPrompt = reasoning.systemPrompt,
                messages = chatMessages,
                tools = tools,
                temperature = 0.7,
                maxTokens = null,
                stream = false,
            )

        // Bind the job id into the coroutine context so GatewayClient can stamp
        // `X-Turn-Ref` on the gateway call. No-op for BYOK/local providers, which
        // never read the context key.
        val events = withTurnRef(jobId) { llmProvider.chat(request).toList() }
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ParsedToolCall>()

        for (event in events) {
            when (event) {
                is StreamEvent.TextDelta -> textParts.add(event.text)
                is StreamEvent.ToolCall -> toolCalls.add(event.call)
                is StreamEvent.TokenUsage -> {
                    lastTokensIn = event.input
                    lastTokensOut = event.output
                    lastTokensCached = event.cached
                    lastCostMicrosUsd = event.costMicrosUsd
                }
                StreamEvent.Done -> { /* done */ }
                is StreamEvent.Error -> logger.error("job={} LLM error: {}", jobId, event.cause)
            }
        }

        // Record cost here (not in afterIteration): a finishing text turn returns
        // from the loop before afterIteration runs, so recording there dropped the
        // cost of every final turn. Recording at the end of each callLlm fires
        // exactly once per LLM call.
        recordCallCost()

        val fullText = textParts.joinToString("")

        if (toolCalls.isNotEmpty()) {
            return RespondOutput.WithToolCalls(toolCalls)
        }
        return RespondOutput.TextOnly(fullText)
    }

    private suspend fun recordCallCost() {
        val durationMs = System.currentTimeMillis() - lastCallStartMs
        if (lastTurnId.isNotEmpty()) {
            costGuard.recordCall(
                turnId = lastTurnId,
                model = modelName,
                tokensIn = lastTokensIn,
                tokensOut = lastTokensOut,
                costMicrosUsd = lastCostMicrosUsd,
                durationMs = durationMs,
                tokensCached = lastTokensCached,
            )
        }
    }

    override suspend fun handleTextResponse(text: String): TextAction {
        memory.appendMessage(
            jobId,
            ConversationMessage(
                id = UUID.randomUUID(),
                role = ChatRole.Assistant,
                content = text,
                toolCalls = emptyList(),
                ts = Clock.System.now(),
            ),
        )
        return TextAction.FinishWith
    }

    override suspend fun executeToolCalls(
        calls: List<ParsedToolCall>,
        ctx: ReasoningContext,
    ): LoopOutcome? {
        val toolCtx = toToolContext(ctx)
        for (call in calls) {
            val outcome = dispatcher.dispatch(call, toolCtx)
            when (outcome) {
                is DispatchOutcome.Result -> {
                    val result = outcome.result
                    val content =
                        when (result) {
                            is ToolResult.Ok -> result.content.toString()
                            is ToolResult.Err -> "ERROR: ${result.message}"
                            is ToolResult.NeedsApproval -> "NEEDS_APPROVAL: ${result.prompt}"
                        }
                    memory.appendMessage(
                        jobId,
                        ConversationMessage(
                            id = UUID.randomUUID(),
                            role = ChatRole.Tool,
                            content = content,
                            toolCalls = listOf(call),
                            ts = Clock.System.now(),
                        ),
                    )
                }
            }
        }
        return null
    }

    private fun toToolContext(ctx: ReasoningContext): ToolContext =
        object : ToolContext {
            override val sessionId: String = ctx.sessionId
            override val turnId: String = ctx.turnId
            override val userId: String = ctx.userId
            override val requestor: Channel = ctx.requestor
            override val workspace: WorkspacePath = ctx.workspace
            override val approvalGate: org.tatrman.kantheon.hebe.api.ApprovalGate = ctx.approvalGate
            override val observer: Observer = ctx.observer
            override val secretLookup: SecretLookup = ctx.secretLookup
        }

    override suspend fun afterIteration(iter: Int) {
        // Cost is recorded at the end of callLlm (see recordCallCost) so it fires on
        // every turn, including a finishing text turn that exits the loop early.
    }

    fun signalEstop() {
        estop = true
    }

    fun signalCancel() {
        cancel = true
    }

    suspend fun run(
        reasoning: Reasoning,
        ctx: ReasoningContext,
        config: LoopConfig,
    ): LoopOutcome = runAgenticLoop(this, reasoning, ctx, config)
}
