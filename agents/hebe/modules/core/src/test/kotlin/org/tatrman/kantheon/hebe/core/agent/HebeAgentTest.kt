package org.tatrman.kantheon.hebe.core.agent

import org.tatrman.kantheon.hebe.api.Channel
import org.tatrman.kantheon.hebe.api.HandleOutcome
import org.tatrman.kantheon.hebe.api.IncomingMessage
import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ProviderCapabilities
import org.tatrman.kantheon.hebe.config.CostSection
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.config.SecretStoreProvider
import org.tatrman.kantheon.hebe.core.compaction.Compactor
import org.tatrman.kantheon.hebe.core.compaction.PreemptivePruner
import org.tatrman.kantheon.hebe.core.cost.CostGuard
import org.tatrman.kantheon.hebe.core.hooks.HookRunner
import org.tatrman.kantheon.hebe.core.submission.SubmissionParser
import org.tatrman.kantheon.hebe.memory.db.DbFactory
import org.tatrman.kantheon.hebe.providers.openai.MockLlmProvider
import org.tatrman.kantheon.hebe.security.approval.ApprovalGate
import org.tatrman.kantheon.hebe.security.approval.PendingApprovalsRepo
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HebeAgentTest {
    private fun makeMsg(
        content: String,
        channelId: String = "cli",
        metadata: JsonObject = JsonObject(emptyMap()),
    ) = IncomingMessage(
        id = UUID.randomUUID(),
        channel = channelId,
        userId = "user1",
        senderId = "user1",
        content = content,
        metadata = metadata,
        receivedAt = Clock.System.now(),
    )

    private fun makeAgent(llm: LlmProvider): HebeAgent {
        val db = DbFactory.openInMemory()
        val observer = mockk<Observer>(relaxed = true)
        val config = mockk<HebeConfig>(relaxed = true)
        every { config.cost } returns CostSection(dailyUsdCap = 10.0, perTurnTokenCap = 100_000)
        val costGuard = CostGuard(db.dataSource, config, observer)
        val memory = mockk<MemoryStore>(relaxed = true)
        coEvery { memory.loadContext(any()) } returns emptyList()
        coEvery { memory.appendMessage(any(), any()) } returns Unit
        val dispatcher = mockk<ToolDispatcher>(relaxed = true)
        val compactorLlm = mockk<LlmProvider>()
        every { compactorLlm.capabilities() } returns
            ProviderCapabilities(streaming = true, toolUse = true, multimodal = false, maxContextTokens = 128_000)
        val compactor = PreemptivePruner(Compactor(compactorLlm, mockk(relaxed = true), config))
        val channel = mockk<Channel> { every { name } returns "cli" }
        val repo = mockk<PendingApprovalsRepo>(relaxed = true)
        val approvalGate = ApprovalGate(repo, ttlMillis = 60_000)
        val secretStore = mockk<SecretStoreProvider>(relaxed = true)
        return HebeAgent(
            sessionManager = SessionManager(),
            submissionParser = SubmissionParser,
            channel = channel,
            memory = memory,
            dispatcher = dispatcher,
            llmProvider = llm,
            costGuard = costGuard,
            compactor = compactor,
            hooks = HookRunner(),
            observer = observer,
            approvalGate = approvalGate,
            secretLookup = mockk(relaxed = true),
            secretStore = secretStore,
            systemPrompt = "assistant",
            toolsProvider = { emptyList() },
            activeSkills = emptyList(),
        )
    }

    @Test
    fun `user input returns Response`() =
        runTest {
            val llm =
                MockLlmProvider
                    .builder()
                    .turn {
                        textDelta("Hello!")
                        done()
                    }.build()
            val agent = makeAgent(llm)
            val outcome = agent.handleMessage(makeMsg("hi"))
            assertTrue(outcome is HandleOutcome.Done)
            assertEquals("Hello!", (outcome as HandleOutcome.Done).reply.text)
        }

    @Test
    fun `quit command returns Stopped`() =
        runTest {
            val llm = MockLlmProvider.builder().build()
            val agent = makeAgent(llm)
            val outcome = agent.handleMessage(makeMsg("/quit"))
            assertTrue(outcome is HandleOutcome.Done)
            assertEquals("Stopped", (outcome as HandleOutcome.Done).reply.text)
        }

    @Test
    fun `status command returns status`() =
        runTest {
            val llm = MockLlmProvider.builder().build()
            val agent = makeAgent(llm)
            val outcome = agent.handleMessage(makeMsg("/status"))
            assertTrue(outcome is HandleOutcome.Done)
            assertEquals("Status: running", (outcome as HandleOutcome.Done).reply.text)
        }

    @Test
    fun `compact command runs compaction`() =
        runTest {
            val llm = MockLlmProvider.builder().build()
            val agent = makeAgent(llm)
            val outcome = agent.handleMessage(makeMsg("/compact"))
            assertTrue(outcome is HandleOutcome.Done)
            assertEquals("Compaction complete", (outcome as HandleOutcome.Done).reply.text)
        }

    @Test
    fun `approval command resolves gate`() =
        runTest {
            val llm = MockLlmProvider.builder().build()
            val agent = makeAgent(llm)
            val outcome = agent.handleMessage(makeMsg("/deny nonexistent-id"))
            assertTrue(outcome is HandleOutcome.Done)
            val msg = (outcome as HandleOutcome.Done).reply.text
            assertTrue(msg.contains("not found") || msg.contains("resolved"))
        }

    @Test
    fun `authMode command stores secret`() =
        runTest {
            val llm = MockLlmProvider.builder().build()
            val agent = makeAgent(llm)
            val meta =
                kotlinx.serialization.json.buildJsonObject {
                    put("authMode", kotlinx.serialization.json.JsonPrimitive("true"))
                }
            val outcome = agent.handleMessage(makeMsg("my-secret-token", metadata = meta))
            assertTrue(outcome is HandleOutcome.Done)
            assertEquals("[credential stored]", (outcome as HandleOutcome.Done).reply.text)
        }
}
