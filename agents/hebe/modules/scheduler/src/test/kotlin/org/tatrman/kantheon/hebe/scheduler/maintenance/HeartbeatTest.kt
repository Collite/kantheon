@file:Suppress("EmptyFunctionBlock", "MaxLineLength", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.scheduler.maintenance

import org.tatrman.kantheon.hebe.scheduler.Services
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

private fun noopMemoryStore() =
    object : org.tatrman.kantheon.hebe.api.MemoryStore {
        override suspend fun search(
            query: String,
            k: Int,
            scope: org.tatrman.kantheon.hebe.api.MemoryScope,
            categories: Set<org.tatrman.kantheon.hebe.api.MemoryCategory>?,
        ) = emptyList<org.tatrman.kantheon.hebe.api.MemoryHit>()

        override suspend fun loadContext(
            conversationId: String,
            limit: Int,
        ) = emptyList<org.tatrman.kantheon.hebe.api.ConversationMessage>()

        override suspend fun appendMessage(
            conversationId: String,
            msg: org.tatrman.kantheon.hebe.api.ConversationMessage,
        ) {}

        override suspend fun appendDoc(
            path: String,
            content: String,
            scope: org.tatrman.kantheon.hebe.api.MemoryScope,
            category: org.tatrman.kantheon.hebe.api.MemoryCategory,
        ) {}

        override suspend fun readDoc(path: String): String? = null

        override suspend fun listDocs(prefix: String): List<String> = emptyList()

        override suspend fun systemPrompt(isGroup: Boolean): String = ""

        override suspend fun snapshot(): org.tatrman.kantheon.hebe.api.MemorySnapshot = org.tatrman.kantheon.hebe.api.MemorySnapshot(0, 0, 0)
    }

private fun noopLlmProvider(response: String) =
    object : org.tatrman.kantheon.hebe.api.LlmProvider {
        override suspend fun chat(req: org.tatrman.kantheon.hebe.api.ChatRequest) =
            kotlinx.coroutines.flow.flowOf(
                org.tatrman.kantheon.hebe.api.StreamEvent
                    .TextDelta(response),
                org.tatrman.kantheon.hebe.api.StreamEvent.Done,
            )

        override fun capabilities() =
            org.tatrman.kantheon.hebe.api.ProviderCapabilities(streaming = false, toolUse = false, multimodal = false, maxContextTokens = 0)
    }

private fun noopApprovalGate() =
    object : org.tatrman.kantheon.hebe.api.ApprovalGate {
        override fun requestIfNeeded(
            tool: org.tatrman.kantheon.hebe.api.Tool,
            args: kotlinx.serialization.json.JsonObject,
            turnId: String,
            channel: String,
            threadExtId: String?,
        ) = kotlinx.coroutines.flow.flowOf(org.tatrman.kantheon.hebe.api.ApprovalStatus.Denied)

        override suspend fun awaitApproval(
            tool: org.tatrman.kantheon.hebe.api.Tool,
            args: kotlinx.serialization.json.JsonObject,
            turnId: String,
            channel: String,
            threadExtId: String?,
        ) = false

        override fun resolve(
            approvalId: String,
            approved: Boolean,
        ) = false
    }

private fun noopObserver() =
    object : org.tatrman.kantheon.hebe.api.Observer {
        override fun event(e: org.tatrman.kantheon.hebe.api.ObserverEvent) {}

        override fun span(
            name: String,
            attrs: Map<String, Any>,
        ): org.tatrman.kantheon.hebe.api.Span = noopSpan()
    }

private fun noopSpan() =
    object : org.tatrman.kantheon.hebe.api.Span {
        override fun setAttribute(
            key: String,
            value: Any,
        ) {}

        override fun recordError(t: Throwable) {}

        override fun close() {}
    }

private fun noopLeakDetector() =
    object : org.tatrman.kantheon.hebe.api.LeakDetector {
        override fun scan(result: org.tatrman.kantheon.hebe.api.ToolResult) = result
    }

private fun noopReceipts() =
    object : org.tatrman.kantheon.hebe.api.Receipts {
        override suspend fun append(partial: org.tatrman.kantheon.hebe.api.PartialReceipt): Long = 0L
    }

private fun createFakeDispatcher(memory: org.tatrman.kantheon.hebe.api.MemoryStore): org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher =
    org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher(
        registry =
            org.tatrman.kantheon.hebe.tools.dispatch
                .ToolRegistry(),
        validators = emptyList(),
        approvalGate = noopApprovalGate(),
        memory = memory,
        observer = noopObserver(),
        leakDetector = noopLeakDetector(),
        receipts = noopReceipts(),
        postureGate =
            org.tatrman.kantheon.hebe.tools.dispatch.PostureGate
                .unrestricted(),
    )

private fun noopCostGuard() =
    mockk<org.tatrman.kantheon.hebe.core.cost.CostGuard>(relaxed = true).also {
        coEvery { it.checkAllowed(any(), any()) } returns org.tatrman.kantheon.hebe.core.cost.CostGuard.CheckResult.Allow
    }

private fun noopCompactor(): org.tatrman.kantheon.hebe.core.compaction.PreemptivePruner {
    val compactorLlm = mockk<org.tatrman.kantheon.hebe.api.LlmProvider>()
    every { compactorLlm.capabilities() } returns
        org.tatrman.kantheon.hebe.api.ProviderCapabilities(streaming = true, toolUse = false, multimodal = false, maxContextTokens = 128_000)
    val workspaceFs = mockk<org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs>(relaxed = true)
    val config =
        org.tatrman.kantheon.hebe.config.HebeConfig
            .default()
    return org.tatrman.kantheon.hebe.core.compaction.PreemptivePruner(
        org.tatrman.kantheon.hebe.core.compaction
            .Compactor(compactorLlm, workspaceFs, config),
    )
}

class HeartbeatTest :
    StringSpec({
        "heartbeat runs without error" {
            runTest {
                val fakeMemory = noopMemoryStore()
                val fakeLlm = noopLlmProvider("OK")
                val fakeDispatcher = createFakeDispatcher(fakeMemory)
                var notified = false
                val notifyChannel =
                    object : Heartbeat.NotifyChannel {
                        override suspend fun notify(
                            title: String,
                            body: String,
                        ) {
                            notified = true
                        }
                    }

                val heartbeat =
                    Heartbeat(
                        services =
                            Services(
                                memory = fakeMemory,
                                dispatcher = fakeDispatcher,
                                llmProvider = fakeLlm,
                                costGuard = noopCostGuard(),
                                compactor = noopCompactor(),
                                observer = noopObserver(),
                            ),
                        modelName = "test",
                        systemPrompt = "",
                        notifyChannel = notifyChannel,
                        heartbeatFilePath = "/nonexistent/HEARTBEAT.md",
                    )

                val result = heartbeat.run()
                result.isSuccess shouldBe true
                notified shouldBe false
            }
        }

        "non-OK response triggers notification" {
            runTest {
                val tmp = kotlin.io.path.createTempFile("HEARTBEAT", ".md")
                tmp.toFile().writeText("- Check disk space")
                try {
                    val fakeMemory = noopMemoryStore()
                    val fakeLlm = noopLlmProvider("Disk space is low")
                    val fakeDispatcher = createFakeDispatcher(fakeMemory)
                    var notified = false
                    val notifyChannel =
                        object : Heartbeat.NotifyChannel {
                            override suspend fun notify(
                                title: String,
                                body: String,
                            ) {
                                notified = true
                            }
                        }

                    val heartbeat =
                        Heartbeat(
                            services =
                                Services(
                                    memory = fakeMemory,
                                    dispatcher = fakeDispatcher,
                                    llmProvider = fakeLlm,
                                    costGuard = noopCostGuard(),
                                    compactor = noopCompactor(),
                                    observer = noopObserver(),
                                ),
                            modelName = "test",
                            systemPrompt = "",
                            notifyChannel = notifyChannel,
                            heartbeatFilePath = tmp.toString(),
                        )

                    val result = heartbeat.run()
                    result.isSuccess shouldBe true
                    notified shouldBe true
                } finally {
                    tmp.toFile().delete()
                }
            }
        }
    })
