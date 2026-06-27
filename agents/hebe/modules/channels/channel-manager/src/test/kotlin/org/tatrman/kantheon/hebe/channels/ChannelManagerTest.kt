package org.tatrman.kantheon.hebe.channels

import org.tatrman.kantheon.hebe.api.Channel
import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.HandleOutcome
import org.tatrman.kantheon.hebe.api.IncomingMessage
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ObserverEvent
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.PendingReason
import org.tatrman.kantheon.hebe.core.agent.HebeAgent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChannelManagerTest {
    private lateinit var agent: HebeAgent
    private lateinit var observer: Observer
    private lateinit var channelManager: ChannelManagerImpl
    private lateinit var mockChannel: Channel
    private lateinit var mockChannelFlow: MutableSharedFlow<IncomingMessage>

    @BeforeEach
    fun setup() {
        agent = mockk(relaxed = true)
        observer =
            mockk {
                every { event(any()) } returns Unit
                every { span(any(), any()) } returns mockk(relaxed = true)
            }
        mockChannelFlow = MutableSharedFlow()
        mockChannel =
            mockk {
                every { name } returns "test-channel"
                coEvery { start(any()) } returns mockChannelFlow
                coEvery { reply(any(), any()) } returns Unit
                coEvery { healthCheck() } returns ChannelHealth.Up
                coEvery { shutdown() } returns Unit
            }
        channelManager = ChannelManagerImpl(agent, observer)
    }

    private fun makeMsg(
        content: String = "hello",
        isAgentBroadcast: Boolean = false,
        triggeringMissionId: String? = null,
    ) = IncomingMessage(
        id = UUID.randomUUID(),
        channel = "test-channel",
        userId = "user",
        senderId = "sender",
        content = content,
        attachments = emptyList(),
        threadId = null,
        metadata = kotlinx.serialization.json.JsonObject(emptyMap()),
        receivedAt = Clock.System.now(),
        isInternal = false,
        isAgentBroadcast = isAgentBroadcast,
        triggeringMissionId = triggeringMissionId,
    )

    @Test
    fun `register adds channel`() =
        runTest {
            channelManager.register(mockChannel)
            assert(channelManager.channelCount == 1)
        }

    @Test
    fun `unregister removes channel`() =
        runTest {
            channelManager.register(mockChannel)
            channelManager.unregister("test-channel")
            assert(channelManager.channelCount == 0)
        }

    @Test
    fun `inject channel is non-null`() {
        assert(channelManager.injectChannel() != null)
    }

    @Test
    fun `start returns active job`() =
        runTest {
            val job = channelManager.start(backgroundScope)
            assert(job.isActive)
            channelManager.shutdown()
        }

    @Test
    fun `register replaces existing channel with shutdown of old`() =
        runTest {
            channelManager.register(mockChannel)
            val newChannel =
                mockk<Channel> {
                    every { name } returns "test-channel"
                    coEvery { start(any()) } returns MutableSharedFlow()
                    coEvery { reply(any(), any()) } returns Unit
                    coEvery { healthCheck() } returns ChannelHealth.Up
                    coEvery { shutdown() } returns Unit
                }
            channelManager.register(newChannel)
            assert(channelManager.channelCount == 1)
            coVerify { mockChannel.shutdown() }
        }

    @Test
    fun `getAllHealth returns health for registered channels`() =
        runTest {
            channelManager.register(mockChannel)
            val healths = channelManager.getAllHealth()
            assert(healths.any { it.first == "test-channel" && it.second == ChannelHealth.Up })
        }

    @Test
    fun `shutdown clears all channels and calls shutdown on each`() =
        runTest {
            channelManager.register(mockChannel)
            channelManager.shutdown()
            assert(channelManager.channelCount == 0)
            coVerify { mockChannel.shutdown() }
        }

    @Test
    fun `isAgentBroadcast message is dropped before handleMessage`() =
        runTest(UnconfinedTestDispatcher()) {
            channelManager.register(mockChannel)
            channelManager.start(backgroundScope)

            val broadcastMsg = makeMsg(isAgentBroadcast = true)
            mockChannelFlow.emit(broadcastMsg)

            coVerify(exactly = 0) { agent.handleMessage(any()) }
            channelManager.shutdown()
        }

    @Test
    fun `normal message is routed to agent handleMessage`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { agent.handleMessage(any()) } returns
                HandleOutcome.Done(
                    reply = OutboundMessage("response"),
                )
            channelManager.register(mockChannel)
            channelManager.start(backgroundScope)

            val msg = makeMsg()
            mockChannelFlow.emit(msg)

            coVerify(atLeast = 1) { agent.handleMessage(any()) }
            channelManager.shutdown()
        }

    @Test
    fun `same triggeringMissionId within window is deduplicated`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { agent.handleMessage(any()) } returns
                HandleOutcome.Done(
                    reply = OutboundMessage("response"),
                )
            channelManager.register(mockChannel)
            channelManager.start(backgroundScope)

            val missionId = "mission-123"
            val msg1 = makeMsg(triggeringMissionId = missionId)
            val msg2 = makeMsg(triggeringMissionId = missionId)

            mockChannelFlow.emit(msg1)
            mockChannelFlow.emit(msg2)

            coVerify(exactly = 1) { agent.handleMessage(any()) }
            channelManager.shutdown()
        }

    @Test
    fun `injectChannel send reaches the agent`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { agent.handleMessage(any()) } returns
                HandleOutcome.Done(
                    reply = OutboundMessage("response"),
                )
            channelManager.register(mockChannel)
            channelManager.start(backgroundScope)
            val inject = channelManager.injectChannel()
            inject.start(backgroundScope)

            inject.send(makeMsg(content = "injected"))

            coVerify(atLeast = 1) { agent.handleMessage(any()) }
            channelManager.shutdown()
        }

    @Test
    fun `HandleOutcome Failed sends error reply to channel and emits TurnEnd failed`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { agent.handleMessage(any()) } returns HandleOutcome.Failed("something broke")
            channelManager.register(mockChannel)
            channelManager.start(backgroundScope)

            mockChannelFlow.emit(makeMsg())

            coVerify(atLeast = 1) { mockChannel.reply(any(), match { it.text.startsWith("Error:") }) }
            verify { observer.event(match { it is ObserverEvent.TurnEnd && (it as ObserverEvent.TurnEnd).outcome == "failed" }) }
            channelManager.shutdown()
        }

    @Test
    fun `HandleOutcome Pending emits TurnEnd pending without calling reply`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { agent.handleMessage(any()) } returns
                HandleOutcome.Pending(
                    reason = PendingReason.AuthEntry(purpose = "test"),
                )
            channelManager.register(mockChannel)
            channelManager.start(backgroundScope)

            mockChannelFlow.emit(makeMsg())

            coVerify(exactly = 0) { mockChannel.reply(any(), any()) }
            verify { observer.event(match { it is ObserverEvent.TurnEnd && (it as ObserverEvent.TurnEnd).outcome == "pending" }) }
            channelManager.shutdown()
        }

    @Test
    fun `HandleOutcome NoResponse emits TurnEnd no_response without calling reply`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { agent.handleMessage(any()) } returns HandleOutcome.NoResponse(cause = "filtered")
            channelManager.register(mockChannel)
            channelManager.start(backgroundScope)

            mockChannelFlow.emit(makeMsg())

            coVerify(exactly = 0) { mockChannel.reply(any(), any()) }
            verify { observer.event(match { it is ObserverEvent.TurnEnd && (it as ObserverEvent.TurnEnd).outcome == "no_response" }) }
            channelManager.shutdown()
        }

    @Test
    fun `message sessionId is propagated to ReplyContext`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { agent.handleMessage(any()) } returns HandleOutcome.Done(reply = OutboundMessage("ok"))
            channelManager.register(mockChannel)
            channelManager.start(backgroundScope)

            val msg = makeMsg().copy(sessionId = "browser-session-xyz")
            mockChannelFlow.emit(msg)

            coVerify { mockChannel.reply(match { it.sessionId == "browser-session-xyz" }, any()) }
            channelManager.shutdown()
        }
}
