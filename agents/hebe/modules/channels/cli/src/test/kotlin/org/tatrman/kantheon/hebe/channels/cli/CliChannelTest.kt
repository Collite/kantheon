package org.tatrman.kantheon.hebe.channels.cli

import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.ReplyContext
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException
import org.jline.terminal.TerminalBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliChannelTest {
    private fun makeReplyContext() =
        ReplyContext(
            incomingId = UUID.randomUUID(),
            sessionId = "cli",
            threadId = null,
        )

    private fun makeScriptedChannel(vararg lines: String): CliChannel {
        var callCount = 0
        val mockReader = mockk<LineReader>(relaxed = true)
        every { mockReader.readLine(any<String>()) } answers {
            if (callCount < lines.size) {
                lines[callCount++]
            } else {
                throw org.jline.reader.EndOfFileException()
            }
        }
        return CliChannel(
            terminalFactory = {
                TerminalBuilder
                    .builder()
                    .streams(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
                    .dumb(true)
                    .build()
            },
            lineReaderFactory = { _ -> mockReader },
        )
    }

    @Test
    fun `name is cli`() {
        assertEquals("cli", CliChannel().name)
    }

    @Test
    fun `supportsDraftUpdates returns true`() {
        assertTrue(CliChannel().supportsDraftUpdates())
    }

    @Test
    fun `healthCheck returns Up`() =
        runTest {
            assertEquals(ChannelHealth.Up, CliChannel().healthCheck())
        }

    @Test
    fun `scripted input produces IncomingMessage on flow`() =
        runTest(UnconfinedTestDispatcher()) {
            val channel = makeScriptedChannel("hello from scripted input")
            val flow = channel.start(backgroundScope)

            val received = withTimeout(2000) { flow.first() }

            assertEquals("hello from scripted input", received.content)
            assertEquals("cli", received.channel)
            assertEquals("operator", received.userId)

            channel.shutdown()
        }

    @Test
    fun `message content is trimmed of leading whitespace`() =
        runTest(UnconfinedTestDispatcher()) {
            val channel = makeScriptedChannel("   trimmed input")
            val flow = channel.start(backgroundScope)

            val received = withTimeout(2000) { flow.first() }
            assertEquals("trimmed input", received.content)

            channel.shutdown()
        }

    @Test
    fun `reply does not throw when terminal is null`() =
        runTest {
            val channel = CliChannel()
            channel.reply(makeReplyContext(), OutboundMessage("Hello"))
        }

    @Test
    fun `broadcast does not throw`() =
        runTest {
            CliChannel().broadcast("user1", OutboundMessage("Hello!"))
        }

    @Test
    fun `updateDraft does not throw when reader is null`() =
        runTest {
            CliChannel().updateDraft(makeReplyContext(), "partial text")
        }

    @Test
    fun `shutdown closes inputChannel so flow terminates`() =
        runTest(UnconfinedTestDispatcher()) {
            val channel = makeScriptedChannel()
            channel.start(backgroundScope)
            channel.shutdown()
            assertFalse(channel.isCancelRequested())
        }

    @Test
    fun `isCancelRequested starts as false`() {
        assertFalse(CliChannel().isCancelRequested())
    }

    @Test
    fun `single UserInterruptException sets isCancelRequested to true`() =
        runTest(UnconfinedTestDispatcher()) {
            var callCount = 0
            val mockReader = mockk<LineReader>(relaxed = true)
            every { mockReader.readLine(any<String>()) } answers {
                when (callCount++) {
                    0 -> throw UserInterruptException("")
                    else -> throw EndOfFileException()
                }
            }
            val channel =
                CliChannel(
                    terminalFactory = {
                        TerminalBuilder
                            .builder()
                            .streams(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
                            .dumb(true)
                            .build()
                    },
                    lineReaderFactory = { _ -> mockReader },
                )
            channel.start(backgroundScope)

            // Allow the REPL loop to run and process the interrupt
            withTimeout(2000) {
                // The REPL should continue running (not exit on single Ctrl-C)
                // and isCancelRequested should become true
                var checked = false
                repeat(50) {
                    if (channel.isCancelRequested()) {
                        checked = true
                        return@repeat
                    }
                    kotlinx.coroutines.delay(10)
                }
                assertTrue(checked, "isCancelRequested should be true after single UserInterruptException")
            }
            channel.shutdown()
        }
}
