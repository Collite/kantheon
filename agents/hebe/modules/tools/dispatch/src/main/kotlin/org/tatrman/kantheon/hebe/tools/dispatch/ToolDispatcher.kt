package org.tatrman.kantheon.hebe.tools.dispatch

import org.tatrman.kantheon.hebe.api.ApprovalGate
import org.tatrman.kantheon.hebe.api.ChatRole
import org.tatrman.kantheon.hebe.api.ConversationMessage
import org.tatrman.kantheon.hebe.api.LeakDetector
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ObserverEvent
import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.PartialReceipt
import org.tatrman.kantheon.hebe.api.Receipts
import org.tatrman.kantheon.hebe.api.Span
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.Validator
import org.tatrman.kantheon.hebe.api.security.ArgsRedactor
import java.util.UUID
import kotlin.time.Clock
import org.slf4j.LoggerFactory

class ToolDispatcher(
    private val registry: ToolRegistry,
    private val validators: List<Validator>,
    private val approvalGate: ApprovalGate,
    private val memory: MemoryStore,
    private val observer: Observer,
    private val leakDetector: LeakDetector,
    private val receipts: Receipts,
    // P2 Stage 2.4 — tool posture. Required (no default) so every dispatch path
    // must make a deliberate posture choice; an omitted gate previously defaulted
    // to unrestricted and silently bypassed posture (e.g. the MCP-server path).
    // Callers with no posture concern pass [PostureGate.unrestricted] explicitly.
    private val postureGate: PostureGate,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val loopDetector = LoopDetector()
    private val dispatchValidators: List<DispatchValidator> = validators.map { it.toDispatchValidator() }

    suspend fun dispatch(
        call: ParsedToolCall,
        ctx: ToolContext,
    ): DispatchOutcome {
        val tool =
            registry.get(call.name)
                ?: return DispatchOutcome.Result(
                    ToolResult.Err("unknown tool: ${call.name}"),
                )

        val startMs = System.currentTimeMillis()
        val span = observer.span("dispatch.${call.name}")

        try {
            span.use {
                // Posture gate (P2 Stage 2.4): a denied family is a *receipted*
                // refusal — it flows through writeReceiptAndMemory like any other
                // state change (the mutation-funnel detekt rule requires this).
                val decision = postureGate.decide(call.name)
                if (decision is PostureDecision.Deny) {
                    span.setAttribute("posture.denied", true)
                    span.setAttribute("posture.family", decision.family.token)
                    val result =
                        ToolResult.Err(
                            "posture: tool '${call.name}' (${decision.family.token}) blocked under restricted posture",
                        )
                    writeReceiptAndMemory(call, result, ctx, span, tool, startMs)
                    return DispatchOutcome.Result(result)
                }

                val loopCount = loopDetector.fingerprint(ctx.turnId, call)
                logger.debug(
                    "dispatching tool={} turnId={} loopCount={}",
                    call.name,
                    ctx.turnId,
                    loopCount,
                )

                val validationResult = runValidators(call, tool, ctx)
                when (validationResult) {
                    is DispatchValidationResult.Deny -> {
                        val result = ToolResult.Err("policy: ${validationResult.reason}")
                        writeReceiptAndMemory(call, result, ctx, span, tool, startMs)
                        return DispatchOutcome.Result(result)
                    }
                    is DispatchValidationResult.RequireApproval -> {
                        val approved =
                            approvalGate.awaitApproval(
                                tool = tool,
                                args = call.args,
                                turnId = ctx.turnId,
                                channel = ctx.requestor.name,
                            )
                        if (!approved) {
                            val result = ToolResult.Err("denied")
                            writeReceiptAndMemory(call, result, ctx, span, tool, startMs)
                            return DispatchOutcome.Result(result)
                        }
                    }
                    DispatchValidationResult.Allow -> { /* continue */ }
                }

                if (loopDetector.shouldForceText(ctx.turnId, call)) {
                    logger.warn(
                        "loop detector forced text for turnId={} tool={}",
                        ctx.turnId,
                        call.name,
                    )
                    val result =
                        ToolResult.Err(
                            "[Loop detector] Repeated identical call; switching to text mode",
                        )
                    writeReceiptAndMemory(call, result, ctx, span, tool, startMs)
                    return DispatchOutcome.Result(result)
                }

                val raw =
                    runCatching {
                        tool.invoke(call.args, ctx)
                    }.getOrElse { ex ->
                        ToolResult.Err("tool exception: ${ex.message}")
                    }

                val scanned = leakDetector.scan(raw)
                val ok = scanned is ToolResult.Ok
                span.setAttribute("tool.name", call.name)
                span.setAttribute("risk", tool.risk.name)
                span.setAttribute("ok", ok)
                writeReceiptAndMemory(call, scanned, ctx, span, tool, startMs)
                return DispatchOutcome.Result(scanned)
            }
        } catch (e: Exception) {
            logger.error("dispatch exception for tool={}", call.name, e)
            return DispatchOutcome.Result(
                ToolResult.Err("dispatch exception: ${e.message}"),
            )
        }
    }

    private suspend fun runValidators(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): DispatchValidationResult {
        var result: DispatchValidationResult = DispatchValidationResult.Allow
        for (validator in dispatchValidators) {
            result = validator.validate(call, tool, ctx)
            if (result !is DispatchValidationResult.Allow) break
        }
        return result
    }

    private suspend fun writeReceiptAndMemory(
        call: ParsedToolCall,
        result: ToolResult,
        ctx: ToolContext,
        span: Span,
        tool: Tool,
        startMs: Long,
    ) {
        val durationMs = System.currentTimeMillis() - startMs
        receipts.append(
            PartialReceipt(
                sessionId = ctx.sessionId,
                turnId = ctx.turnId,
                tool = call.name,
                argsRedacted = ArgsRedactor.INSTANCE.redact(call.args).toString(),
                risk = tool.risk.name,
                durationMs = durationMs,
                ok = result is ToolResult.Ok,
            ),
        )
        memory.appendMessage(
            ctx.sessionId,
            ConversationMessage(
                id = UUID.randomUUID(),
                role = ChatRole.Tool,
                content = serializeResult(result),
                toolCalls = listOf(call),
                ts = Clock.System.now(),
            ),
        )
        observer.event(
            ObserverEvent.ToolDispatched(
                ctx.turnId,
                call.name,
                durationMs,
                ok = result is ToolResult.Ok,
            ),
        )
    }

    private fun serializeResult(result: ToolResult): String =
        when (result) {
            is ToolResult.Ok -> result.content.toString()
            is ToolResult.Err -> "ERROR: ${result.message}"
            is ToolResult.NeedsApproval -> "NEEDS_APPROVAL: ${result.prompt}"
        }
}
