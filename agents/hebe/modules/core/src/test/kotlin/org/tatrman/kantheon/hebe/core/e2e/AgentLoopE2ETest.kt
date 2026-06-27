package org.tatrman.kantheon.hebe.core.e2e

import org.tatrman.kantheon.hebe.api.Channel
import org.tatrman.kantheon.hebe.api.LeakDetector
import org.tatrman.kantheon.hebe.api.LoopConfig
import org.tatrman.kantheon.hebe.api.LoopOutcome
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ProviderCapabilities
import org.tatrman.kantheon.hebe.api.Receipts
import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Span
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.config.CostSection
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.core.compaction.Compactor
import org.tatrman.kantheon.hebe.core.compaction.PreemptivePruner
import org.tatrman.kantheon.hebe.core.cost.CostGuard
import org.tatrman.kantheon.hebe.core.delegate.ChatDelegate
import org.tatrman.kantheon.hebe.memory.db.DbFactory
import org.tatrman.kantheon.hebe.providers.openai.MockLlmProvider
import org.tatrman.kantheon.hebe.security.approval.ApprovalGate
import org.tatrman.kantheon.hebe.security.approval.PendingApprovalsRepo
import org.tatrman.kantheon.hebe.tools.dispatch.PostureGate
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import org.tatrman.kantheon.hebe.tools.dispatch.ToolRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * End-to-end test: mock LLM emits a tool call on turn 1, tool executes, receipt is written,
 * memory is appended, and turn 2 returns a text response.
 */
class AgentLoopE2ETest {
    private val memory = mockk<MemoryStore>()
    private val observer = mockk<Observer>(relaxed = true)
    private val receipts = mockk<Receipts>(relaxed = true)
    private val span = mockk<Span>(relaxed = true)

    init {
        every { observer.span(any()) } returns span
        every { observer.event(any()) } returns Unit
        coEvery { receipts.append(any()) } returns 1L
        coEvery { memory.appendMessage(any(), any()) } returns Unit
        coEvery { memory.loadContext(any()) } returns emptyList()
    }

    private fun reasoning(
        systemPrompt: String = "assistant",
        latestUserMessage: String = "",
    ) = object : org.tatrman.kantheon.hebe.api.Reasoning {
        override val systemPrompt = systemPrompt
        override val activeSkills = listOf<String>()
        override val latestUserMessage = latestUserMessage
    }

    private fun ctx(turnId: String = "t1") =
        object : org.tatrman.kantheon.hebe.api.ReasoningContext {
            override val sessionId = "sess1"
            override val turnId = turnId
            override val userId = "u1"
            override val requestor = mockk<Channel> { every { name } returns "cli" }
            override val workspace =
                org.tatrman.kantheon.hebe.api.workspace
                    .WorkspacePath(".")
            override val approvalGate = mockk<org.tatrman.kantheon.hebe.api.ApprovalGate>(relaxed = true)
            override val observer = this@AgentLoopE2ETest.observer
            override val secretLookup = mockk<org.tatrman.kantheon.hebe.api.SecretLookup>(relaxed = true)
        }

    @Test
    fun `multi-tool turn dispatched, receipt written, memory appended, final text returned`() =
        runTest {
            val toolCallArgs =
                buildJsonObject {
                    put("msg", JsonPrimitive("hello"))
                }

            val llm =
                MockLlmProvider
                    .builder()
                    .turn {
                        toolCall("call_1", "echo", mapOf("msg" to "hello"))
                        tokenUsage(50, 10)
                        done()
                    }.turn {
                        textDelta("All done")
                        tokenUsage(30, 8)
                        done()
                    }.build()

            val echoTool =
                object : Tool {
                    override val spec = ToolSpec("echo", "echoes input", JsonObject(emptyMap()))
                    override val risk = RiskLevel.Low

                    override suspend fun invoke(
                        args: JsonObject,
                        ctx: ToolContext,
                    ) = ToolResult.Ok(args["msg"] ?: JsonPrimitive(""))
                }

            val registry = ToolRegistry().also { it.register(echoTool) }
            val repo = mockk<PendingApprovalsRepo>(relaxed = true)
            val approvalGate = ApprovalGate(repo, ttlMillis = 60_000)
            val dispatcher =
                ToolDispatcher(
                    registry = registry,
                    validators = emptyList(),
                    approvalGate = approvalGate,
                    memory = memory,
                    observer = observer,
                    leakDetector =
                        object : LeakDetector {
                            override fun scan(result: ToolResult) = result
                        },
                    receipts = receipts,
                    postureGate = PostureGate.unrestricted(),
                )

            val db = DbFactory.openInMemory()
            val config = mockk<HebeConfig>(relaxed = true)
            every { config.cost } returns CostSection(dailyUsdCap = 10.0, perTurnTokenCap = 100_000)
            val costGuard = CostGuard(db.dataSource, config, observer)
            val compactorLlm = mockk<org.tatrman.kantheon.hebe.api.LlmProvider>()
            every { compactorLlm.capabilities() } returns
                ProviderCapabilities(streaming = true, toolUse = true, multimodal = false, maxContextTokens = 128_000)
            val compactor = PreemptivePruner(Compactor(compactorLlm, mockk(relaxed = true), config))

            val delegate =
                ChatDelegate(
                    sessionId = "sess1",
                    channel = mockk { every { name } returns "cli" },
                    memory = memory,
                    dispatcher = dispatcher,
                    llmProvider = llm,
                    costGuard = costGuard,
                    compactor = compactor,
                    observer = observer,
                    systemPrompt = "you are helpful",
                    toolsProvider = { listOf(echoTool.spec) },
                    modelName = "test-model",
                    sessionMutex = Mutex(),
                )

            val result = delegate.run(reasoning(), ctx("e2e-turn"), LoopConfig(maxIterations = 5))

            assertEquals(LoopOutcome.Response("All done"), result)

            coVerify {
                receipts.append(
                    match {
                        it.tool == "echo" && it.ok && it.turnId == "e2e-turn"
                    },
                )
            }
            coVerify(atLeast = 1) { memory.appendMessage(eq("sess1"), any()) }
        }

    @Test
    fun `single text turn returns Response without tool dispatch`() =
        runTest {
            val llm =
                MockLlmProvider
                    .builder()
                    .turn {
                        textDelta("Just text")
                        done()
                    }.build()

            val dispatcher = mockk<ToolDispatcher>(relaxed = true)
            val db = DbFactory.openInMemory()
            val config = mockk<HebeConfig>(relaxed = true)
            every { config.cost } returns CostSection(dailyUsdCap = 10.0, perTurnTokenCap = 100_000)
            val costGuard = CostGuard(db.dataSource, config, observer)
            val compactorLlm = mockk<org.tatrman.kantheon.hebe.api.LlmProvider>()
            every { compactorLlm.capabilities() } returns
                ProviderCapabilities(streaming = true, toolUse = true, multimodal = false, maxContextTokens = 128_000)
            val compactor = PreemptivePruner(Compactor(compactorLlm, mockk(relaxed = true), config))

            val delegate =
                ChatDelegate(
                    sessionId = "sess1",
                    channel = mockk { every { name } returns "cli" },
                    memory = memory,
                    dispatcher = dispatcher,
                    llmProvider = llm,
                    costGuard = costGuard,
                    compactor = compactor,
                    observer = observer,
                    systemPrompt = "you are helpful",
                    toolsProvider = { emptyList() },
                    modelName = "test-model",
                    sessionMutex = Mutex(),
                )

            val result = delegate.run(reasoning(), ctx(), LoopConfig(maxIterations = 3))

            assertEquals(LoopOutcome.Response("Just text"), result)
            coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
            coVerify { memory.appendMessage("sess1", match { it.content == "Just text" }) }
        }

    private fun buildJsonObject(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        kotlinx.serialization.json.buildJsonObject(block)
}
