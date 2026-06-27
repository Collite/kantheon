package org.tatrman.kantheon.hebe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface Observer {
    fun event(e: ObserverEvent)

    fun span(
        name: String,
        attrs: Map<String, Any> = emptyMap(),
    ): Span
}

interface Span : AutoCloseable {
    fun setAttribute(
        key: String,
        value: Any,
    )

    fun recordError(t: Throwable)
}

@Serializable
sealed class ObserverEvent {
    @Serializable
    @SerialName("turn_start")
    data class TurnStart(
        val sessionId: String,
        val turnId: String,
        val channel: String? = null,
    ) : ObserverEvent()

    @Serializable
    @SerialName("turn_end")
    data class TurnEnd(
        val sessionId: String,
        val turnId: String,
        val outcome: String,
        val channel: String? = null,
    ) : ObserverEvent()

    @Serializable
    @SerialName("tool_dispatched")
    data class ToolDispatched(
        val turnId: String,
        val tool: String,
        val durationMs: Long,
        val ok: Boolean,
    ) : ObserverEvent()

    @Serializable
    @SerialName("llm_call")
    data class LlmCall(
        val turnId: String,
        val tokensIn: Int,
        val tokensOut: Int,
        val ms: Long,
    ) : ObserverEvent()

    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(
        val turnId: String,
        val tool: String,
    ) : ObserverEvent()

    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(
        val turnId: String,
        val approved: Boolean,
    ) : ObserverEvent()

    @Serializable
    @SerialName("memory_db_ready")
    data class MemoryDbReady(
        val version: String?,
        val applied: Int,
    ) : ObserverEvent()

    @Serializable
    @SerialName("plugin_loaded")
    data class PluginLoaded(
        val pluginId: String,
    ) : ObserverEvent()

    @Serializable
    @SerialName("leak_detected")
    data class LeakDetected(
        val toolName: String,
        val rule: String,
        val severity: String,
    ) : ObserverEvent()
}
