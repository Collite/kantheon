package org.tatrman.kantheon.hebe.core.delegate

import org.tatrman.kantheon.hebe.api.Channel
import org.tatrman.kantheon.hebe.api.ChatRole
import org.tatrman.kantheon.hebe.api.ConversationMessage
import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.api.LoopConfig
import org.tatrman.kantheon.hebe.api.LoopOutcome
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ProviderCapabilities
import org.tatrman.kantheon.hebe.config.CostSection
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.core.compaction.Compactor
import org.tatrman.kantheon.hebe.core.compaction.PreemptivePruner
import org.tatrman.kantheon.hebe.core.cost.CostGuard
import org.tatrman.kantheon.hebe.memory.db.DbFactory
import org.tatrman.kantheon.hebe.providers.openai.MockLlmProvider
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatDelegateTest {
    private fun makeDelegate(
        llm: LlmProvider,
        dailyCap: Double = 10.0,
    ): Pair<ChatDelegate, MockDeps> {
        val db = DbFactory.openInMemory()
        val observer = mockk<Observer>(relaxed = true)
        val config = mockk<HebeConfig>(relaxed = true)
        every { config.cost } returns CostSection(dailyUsdCap = dailyCap, perTurnTokenCap = 100_000)
        val costGuard = CostGuard(db.dataSource, config, observer)
        val memory = mockk<MemoryStore>(relaxed = true)
        coEvery { memory.loadContext(any()) } returns emptyList()
        val dispatcher = mockk<ToolDispatcher>(relaxed = true)
        val compactorLlm = mockk<LlmProvider>()
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
        return delegate to MockDeps(memory, dispatcher, costGuard)
    }

    data class MockDeps(
        val memory: MemoryStore,
        val dispatcher: ToolDispatcher,
        val costGuard: CostGuard,
    )

    private val reasoning =
        object : org.tatrman.kantheon.hebe.api.Reasoning {
            override val systemPrompt = "you are helpful"
            override val activeSkills = listOf<String>()
            override val latestUserMessage = ""
        }

    private fun ctx(turnId: String = "turn1") =
        object : org.tatrman.kantheon.hebe.api.ReasoningContext {
            override val sessionId = "sess1"
            override val turnId = turnId
            override val userId = "user1"
            override val requestor = mockk<Channel> { every { name } returns "cli" }
            override val workspace =
                org.tatrman.kantheon.hebe.api.workspace
                    .WorkspacePath(".")
            override val approvalGate = mockk<org.tatrman.kantheon.hebe.api.ApprovalGate>(relaxed = true)
            override val observer = mockk<Observer>(relaxed = true)
            override val secretLookup = mockk<org.tatrman.kantheon.hebe.api.SecretLookup>(relaxed = true)
        }

    @Test
    fun `run returns Response for simple text turn`() =
        runTest {
            val llm =
                MockLlmProvider
                    .builder()
                    .turn {
                        textDelta("Hello!")
                        done()
                    }.build()
            val (delegate, _) = makeDelegate(llm)
            val result = delegate.run(reasoning, ctx(), LoopConfig(maxIterations = 5))
            assertEquals(LoopOutcome.Response("Hello!"), result)
        }

    @Test
    fun `run records cost after LLM call`() =
        runTest {
            val llm =
                MockLlmProvider
                    .builder()
                    .turn {
                        textDelta("Hi")
                        tokenUsage(100, 50)
                        done()
                    }.build()
            val (delegate, deps) = makeDelegate(llm)
            delegate.run(reasoning, ctx("turn42"), LoopConfig(maxIterations = 3))
            val result = deps.costGuard.checkAllowed("other-turn")
            assertEquals(org.tatrman.kantheon.hebe.core.cost.CostGuard.CheckResult.Allow, result)
        }

    @Test
    fun `gateway turn persists the provider-returned cost into daily spend`() =
        runTest {
            // Provider returns a real cost ⇒ delegate must thread it into
            // CostGuard.recordCall so queryDailySpend is non-zero (DEFECT A).
            val llm =
                MockLlmProvider
                    .builder()
                    .turn {
                        textDelta("Hi")
                        tokenUsage(input = 1000, output = 500, cached = 20, costMicrosUsd = 900_000L)
                        done()
                    }.build()
            val (delegate, deps) = makeDelegate(llm, dailyCap = 0.5)
            // First turn allowed (no prior spend), records $0.90 > $0.50 cap.
            delegate.run(reasoning, ctx("turn-cost"), LoopConfig(maxIterations = 2))
            // The recorded gateway cost now trips the daily cap.
            val result = deps.costGuard.checkAllowed("next")
            assertTrue(result is org.tatrman.kantheon.hebe.core.cost.CostGuard.CheckResult.DenyDaily)
        }

    @Test
    fun `gateway turn ships a non-empty X-Turn-Ref through the real provider`() =
        runTest {
            // DEFECT B — the delegate must wrap the chat call in withTurnRef so the
            // GatewayClient stamps X-Turn-Ref. Drive the *real* OpenAiCompatProvider
            // over a GatewayClient MockEngine and assert the header on the wire.
            val captured = mutableListOf<HttpRequestData>()
            val sse =
                """
                data: {"delta":{"content":"hi"}}

                data: {"usage":{"prompt_tokens":11,"completion_tokens":3,"cost":0.0002},"object":"chunk"}

                data: [DONE]
                """.trimIndent()
            val engine =
                MockEngine { request ->
                    captured.add(request)
                    respond(
                        content = ByteReadChannel(sse),
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                }
            val client =
                org.tatrman.kantheon.hebe.providers.openai.GatewayClient
                    .build(apiKey = "gw-secret", costCenter = "hebe/bora", engine = engine)
            val provider =
                org.tatrman.kantheon.hebe.providers.openai.OpenAiCompatProvider(
                    baseUrl = "https://llm-gateway.example.com/v1",
                    defaultModel = "gw-model",
                    httpClient = client,
                )

            val (delegate, deps) = makeDelegate(provider, dailyCap = 100.0)
            delegate.run(reasoning, ctx("turn-xref-99"), LoopConfig(maxIterations = 2))

            // X-Turn-Ref carried the turn id on the gateway request.
            assertEquals(
                "turn-xref-99",
                captured.first().headers[
                    org.tatrman.kantheon.hebe.providers.openai.GatewayClient.HEADER_TURN_REF,
                ],
            )
            // And the gateway-returned cost ($0.0002 = 200 micro-USD) was recorded.
            val result = deps.costGuard.checkAllowed("next")
            assertEquals(org.tatrman.kantheon.hebe.core.cost.CostGuard.CheckResult.Allow, result)
        }

    @Test
    fun `run stops at max iterations`() =
        runTest {
            // Text responses always FinishWith, so we need tool calls to keep looping.
            val builder = MockLlmProvider.builder()
            repeat(5) {
                builder.turn {
                    toolCall("call_id", "echo", emptyMap())
                    done()
                }
            }
            val llm = builder.build()
            val (delegate, deps) = makeDelegate(llm)
            coEvery { deps.dispatcher.dispatch(any(), any()) } returns
                org.tatrman.kantheon.hebe.tools.dispatch.DispatchOutcome.Result(
                    org.tatrman.kantheon.hebe.api.ToolResult
                        .Ok(kotlinx.serialization.json.JsonPrimitive("ok")),
                )
            val result = delegate.run(reasoning, ctx(), LoopConfig(maxIterations = 3))
            assertEquals(LoopOutcome.MaxIterations, result)
        }

    @Test
    fun `beforeLlmCall denies when daily budget exceeded`() =
        runTest {
            val llm = MockLlmProvider.builder().build()
            val (delegate, deps) = makeDelegate(llm, dailyCap = 0.000001)
            deps.costGuard.recordCall("prev", "m", 100, 100, 1_000_000L)
            val result = delegate.run(reasoning, ctx(), LoopConfig(maxIterations = 3))
            assertTrue(result is LoopOutcome.Failure)
            assertTrue((result as LoopOutcome.Failure).message.contains("budget"))
        }

    @Test
    fun `compacted history is used in next callLlm`() =
        runTest {
            val llm =
                MockLlmProvider
                    .builder()
                    .turn {
                        textDelta("compacted response")
                        done()
                    }.build()
            val (delegate, deps) = makeDelegate(llm)
            val compactedMsg =
                ConversationMessage(
                    id = UUID.randomUUID(),
                    role = ChatRole.User,
                    content = "compacted",
                    toolCalls = emptyList(),
                    ts = Clock.System.now(),
                )
            coEvery { deps.memory.loadContext(any()) } returns listOf(compactedMsg)
            val result = delegate.run(reasoning, ctx(), LoopConfig(maxIterations = 2))
            assertEquals(LoopOutcome.Response("compacted response"), result)
        }
}
