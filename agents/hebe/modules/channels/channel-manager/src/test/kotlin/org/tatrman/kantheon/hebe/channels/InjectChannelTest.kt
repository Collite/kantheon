package org.tatrman.kantheon.hebe.channels

import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.IncomingMessage
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InjectChannelTest {
    private fun makeMsg(content: String = "test") =
        IncomingMessage(
            id = UUID.randomUUID(),
            channel = "test",
            userId = "user1",
            senderId = "sender1",
            content = content,
            attachments = emptyList(),
            threadId = null,
            metadata = kotlinx.serialization.json.JsonObject(emptyMap()),
            receivedAt = Clock.System.now(),
        )

    @Test
    fun `send returns true on success`() =
        runTest {
            val channel = InjectChannel(capacity = 10)
            assertTrue(channel.send(makeMsg()))
            channel.shutdown()
        }

    @Test
    fun `send returns false when closed`() =
        runTest {
            val channel = InjectChannel(capacity = 10)
            channel.shutdown()
            assertFalse(channel.send(makeMsg()))
        }

    @Test
    fun `message sent via send arrives on the flow`() =
        runTest {
            val channel = InjectChannel(capacity = 10)
            val flow = channel.start(backgroundScope)

            val msg = makeMsg("hello from inject")
            channel.send(msg)

            val received = withTimeout(1000) { flow.first() }
            assertEquals(msg.content, received.content)
            channel.shutdown()
        }

    @Test
    fun `DROP_OLDEST overflow does not suspend sender`() =
        runTest {
            val capacity = 5
            val channel = InjectChannel(capacity = capacity)
            channel.start(backgroundScope)

            repeat(capacity + 3) { i ->
                val sent = channel.send(makeMsg("msg-$i"))
                assertTrue(sent, "send($i) should succeed with DROP_OLDEST")
            }
            channel.shutdown()
        }

    @Test
    fun `name is inject`() {
        assertEquals("inject", InjectChannel().name)
    }

    @Test
    fun `supportsDraftUpdates returns false`() {
        assertFalse(InjectChannel().supportsDraftUpdates())
    }

    @Test
    fun `healthCheck returns Up`() =
        runTest {
            assertEquals(ChannelHealth.Up, InjectChannel().healthCheck())
        }
}
