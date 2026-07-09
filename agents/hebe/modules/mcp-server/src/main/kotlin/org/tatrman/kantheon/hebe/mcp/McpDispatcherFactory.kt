package org.tatrman.kantheon.hebe.mcp

import org.tatrman.kantheon.hebe.api.ApprovalGate
import org.tatrman.kantheon.hebe.api.ApprovalStatus
import org.tatrman.kantheon.hebe.api.LeakDetector
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ObserverEvent
import org.tatrman.kantheon.hebe.api.PartialReceipt
import org.tatrman.kantheon.hebe.api.Receipts
import org.tatrman.kantheon.hebe.api.Span
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.Validator
import org.tatrman.kantheon.hebe.tools.dispatch.PostureGate
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import org.tatrman.kantheon.hebe.tools.dispatch.ToolRegistry
import kotlinx.serialization.json.JsonObject

object McpDispatcherFactory {
    private object DenyAllApprovalGate : ApprovalGate {
        override fun requestIfNeeded(
            tool: Tool,
            args: JsonObject,
            turnId: String,
            channel: String,
            threadExtId: String?,
        ) = kotlinx.coroutines.flow.flowOf(ApprovalStatus.Denied)

        override suspend fun awaitApproval(
            tool: Tool,
            args: JsonObject,
            turnId: String,
            channel: String,
            threadExtId: String?,
        ): Boolean = false

        override fun resolve(
            approvalId: String,
            approved: Boolean,
        ): Boolean = false
    }

    private object NoopObserver : Observer {
        override fun event(e: ObserverEvent) {}

        override fun span(
            name: String,
            attrs: Map<String, Any>,
        ): Span = NoopSpan
    }

    private object NoopSpan : Span {
        override fun setAttribute(
            key: String,
            value: Any,
        ) {}

        override fun recordError(t: Throwable) {}

        override fun close() {}
    }

    private object NoopLeakDetector : LeakDetector {
        override fun scan(result: ToolResult): ToolResult = result
    }

    private object NoopReceipts : Receipts {
        override suspend fun append(partial: PartialReceipt): Long = 0L
    }

    private object NoopMemoryStore : org.tatrman.kantheon.hebe.api.MemoryStore {
        override suspend fun appendMessage(
            conversationId: String,
            msg: org.tatrman.kantheon.hebe.api.ConversationMessage,
        ) {}

        override suspend fun loadContext(
            conversationId: String,
            limit: Int,
        ): List<org.tatrman.kantheon.hebe.api.ConversationMessage> = emptyList()

        override suspend fun search(
            query: String,
            k: Int,
            scope: org.tatrman.kantheon.hebe.api.MemoryScope,
            categories: Set<org.tatrman.kantheon.hebe.api.MemoryCategory>?,
        ): List<org.tatrman.kantheon.hebe.api.MemoryHit> = emptyList()

        override suspend fun appendDoc(
            path: String,
            content: String,
            scope: org.tatrman.kantheon.hebe.api.MemoryScope,
            category: org.tatrman.kantheon.hebe.api.MemoryCategory,
        ) {}

        override suspend fun readDoc(path: String): String? = null

        override suspend fun listDocs(prefix: String): List<String> = emptyList()

        override suspend fun systemPrompt(isGroup: Boolean): String = ""

        override suspend fun snapshot(): org.tatrman.kantheon.hebe.api.MemorySnapshot =
            org.tatrman.kantheon.hebe.api
                .MemorySnapshot(0, 0, 0)
    }

    /**
     * The MCP-server dispatch path. [postureGate] is **required**: a remote MCP
     * caller can reach the dangerous tool families (filesystem, shell, git), so
     * the posture must be the axis-resolved gate — not the unrestricted default
     * that this path previously fell back to (P2 Stage 2.4 fix).
     */
    fun createLightweightDispatcher(
        registry: ToolRegistry,
        validators: List<Validator>,
        receipts: Receipts,
        postureGate: PostureGate,
    ): ToolDispatcher =
        ToolDispatcher(
            registry = registry,
            validators = validators,
            approvalGate = DenyAllApprovalGate,
            memory = NoopMemoryStore,
            observer = NoopObserver,
            leakDetector = NoopLeakDetector,
            receipts = receipts,
            postureGate = postureGate,
        )
}
