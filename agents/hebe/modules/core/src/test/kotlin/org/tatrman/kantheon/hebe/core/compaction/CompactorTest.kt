package org.tatrman.kantheon.hebe.core.compaction

import org.tatrman.kantheon.hebe.api.ChatRole
import org.tatrman.kantheon.hebe.api.ConversationMessage
import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.api.ProviderCapabilities
import org.tatrman.kantheon.hebe.api.StreamEvent
import org.tatrman.kantheon.hebe.config.CostSection
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompactorTest {
    private fun msg(content: String) =
        ConversationMessage(
            id = UUID.randomUUID(),
            role = ChatRole.User,
            content = content,
            toolCalls = emptyList(),
            ts = Clock.System.now(),
        )

    private fun makeCompactor(
        maxTokens: Int = 128_000,
        threshold: Double = 0.6,
    ): Compactor {
        val llm = mockk<LlmProvider>()
        every { llm.capabilities() } returns
            ProviderCapabilities(
                streaming = true,
                toolUse = true,
                multimodal = false,
                maxContextTokens = maxTokens,
            )
        coEvery { llm.chat(any()) } returns
            flowOf(StreamEvent.TextDelta("summary text"), StreamEvent.Done)
        val workspace = mockk<WorkspaceFs>(relaxed = true)
        val config = mockk<HebeConfig>(relaxed = true)
        every { config.cost } returns CostSection(compactionThreshold = threshold)
        every { config.llm } returns
            mockk {
                every { defaultModel } returns "gpt-4"
            }
        return Compactor(llm, workspace, config)
    }

    @Test
    fun `maybeCompact returns identity when under threshold`() =
        runTest {
            val compactor = makeCompactor(maxTokens = 128_000, threshold = 0.6)
            val history = listOf(msg("short"))
            val result = compactor.maybeCompact(history, "t1")
            assertFalse(result.compacted)
            assertEquals(history, result.messages)
        }

    @Test
    fun `maybeCompact step2 summarises long history`() =
        runTest {
            val compactor = makeCompactor(maxTokens = 100, threshold = 0.01)
            val history = (1..20).map { msg("word ".repeat(10)) }
            val result = compactor.maybeCompact(history, "t1")
            assertTrue(result.compacted)
            assertTrue(result.messages.size < history.size)
            assertTrue(
                result.messages
                    .first()
                    .content
                    .startsWith("[Summary]"),
            )
        }

    @Test
    fun `PreemptivePruner delegates to Compactor`() =
        runTest {
            val compactor = makeCompactor(maxTokens = 128_000, threshold = 0.6)
            val pruner = PreemptivePruner(compactor)
            val history = listOf(msg("hello"))
            val result = pruner.prune(history, "t1")
            assertFalse(result.compacted)
        }
}
