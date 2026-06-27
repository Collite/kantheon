package org.tatrman.kantheon.hebe.core.hooks

import org.tatrman.kantheon.hebe.api.IncomingMessage
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.ToolContext
import org.slf4j.LoggerFactory

interface BeforeInbound {
    suspend fun apply(msg: IncomingMessage): IncomingMessage?
}

interface BeforeOutbound {
    suspend fun apply(msg: OutboundMessage): OutboundMessage?
}

interface BeforeToolCall {
    suspend fun apply(
        call: ParsedToolCall,
        ctx: ToolContext,
    ): ParsedToolCall?
}

interface OnSessionStart {
    suspend fun apply(sessionId: String)
}

interface OnSessionEnd {
    suspend fun apply(
        sessionId: String,
        outcome: String,
    )
}

class HookRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val beforeInboundHooks = mutableListOf<BeforeInbound>()
    private val beforeOutboundHooks = mutableListOf<BeforeOutbound>()
    private val beforeToolCallHooks = mutableListOf<BeforeToolCall>()
    private val onSessionStartHooks = mutableListOf<OnSessionStart>()
    private val onSessionEndHooks = mutableListOf<OnSessionEnd>()

    fun register(hook: BeforeInbound) {
        beforeInboundHooks.add(hook)
    }

    fun register(hook: BeforeOutbound) {
        beforeOutboundHooks.add(hook)
    }

    fun register(hook: BeforeToolCall) {
        beforeToolCallHooks.add(hook)
    }

    fun register(hook: OnSessionStart) {
        onSessionStartHooks.add(hook)
    }

    fun register(hook: OnSessionEnd) {
        onSessionEndHooks.add(hook)
    }

    suspend fun runBeforeInbound(msg: IncomingMessage): IncomingMessage? {
        var current: IncomingMessage? = msg
        for (hook in beforeInboundHooks) {
            current = runCatching { hook.apply(current!!) }.getOrNull()
            if (current == null) break
        }
        return current
    }

    suspend fun runBeforeOutbound(msg: OutboundMessage): OutboundMessage? {
        var current: OutboundMessage? = msg
        for (hook in beforeOutboundHooks) {
            current = runCatching { hook.apply(current!!) }.getOrNull()
            if (current == null) break
        }
        return current
    }

    suspend fun runBeforeToolCall(
        call: ParsedToolCall,
        ctx: ToolContext,
    ): ParsedToolCall? {
        var current: ParsedToolCall? = call
        for (hook in beforeToolCallHooks) {
            current = runCatching { hook.apply(current!!, ctx) }.getOrNull()
            if (current == null) break
        }
        return current
    }

    suspend fun runOnSessionStart(sessionId: String) {
        for (hook in onSessionStartHooks) {
            runCatching { hook.apply(sessionId) }
        }
    }

    suspend fun runOnSessionEnd(
        sessionId: String,
        outcome: String,
    ) {
        for (hook in onSessionEndHooks) {
            runCatching { hook.apply(sessionId, outcome) }
        }
    }
}
