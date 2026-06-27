package org.tatrman.kantheon.hebe.channels.telegram

import io.mockk.mockk
import io.mockk.verify
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.meta.api.objects.message.Message

class DraftThrottlerTest {
    private fun makeSender() = mockk<(Long, Int, String) -> Message?>(relaxed = true)

    @Test
    fun `first call is always sent immediately`() =
        runTest(UnconfinedTestDispatcher()) {
            val throttler = DraftThrottler()
            val sender = makeSender()

            throttler.throttle(backgroundScope, messageId = 1, chatId = 123L, newText = "first", sender = sender)

            verify(exactly = 1) { sender.invoke(123L, 1, "first") }
        }

    @Test
    fun `second call within interval is suppressed`() =
        runTest(UnconfinedTestDispatcher()) {
            val throttler = DraftThrottler(minInterval = kotlin.time.Duration.parse("1h"))
            val sender = makeSender()

            throttler.throttle(backgroundScope, messageId = 1, chatId = 123L, newText = "first", sender = sender)
            throttler.throttle(backgroundScope, messageId = 1, chatId = 123L, newText = "second", sender = sender)

            verify(exactly = 1) { sender.invoke(any(), any(), any()) }
            verify(exactly = 0) { sender.invoke(123L, 1, "second") }
        }

    @Test
    fun `call is sent when chars changed exceed threshold`() =
        runTest(UnconfinedTestDispatcher()) {
            val throttler = DraftThrottler(minCharsChanged = 5)
            val sender = makeSender()

            throttler.throttle(backgroundScope, messageId = 1, chatId = 123L, newText = "ab", sender = sender)
            throttler.throttle(backgroundScope, messageId = 1, chatId = 123L, newText = "ab + much more text", sender = sender)

            verify(exactly = 2) { sender.invoke(any(), any(), any()) }
        }

    @Test
    fun `flushAll clears pending state so next call starts fresh`() =
        runTest(UnconfinedTestDispatcher()) {
            val throttler = DraftThrottler(minInterval = kotlin.time.Duration.parse("1h"))
            val sender = makeSender()

            throttler.throttle(backgroundScope, messageId = 1, chatId = 123L, newText = "first", sender = sender)
            throttler.flushAll()
            throttler.throttle(backgroundScope, messageId = 1, chatId = 123L, newText = "after-flush", sender = sender)

            verify(exactly = 1) { sender.invoke(123L, 1, "first") }
            verify(exactly = 1) { sender.invoke(123L, 1, "after-flush") }
        }

    @Test
    fun `different message ids are throttled independently`() =
        runTest(UnconfinedTestDispatcher()) {
            val throttler = DraftThrottler(minInterval = kotlin.time.Duration.parse("1h"))
            val sender = makeSender()

            throttler.throttle(backgroundScope, messageId = 1, chatId = 123L, newText = "msg1", sender = sender)
            throttler.throttle(backgroundScope, messageId = 2, chatId = 123L, newText = "msg2", sender = sender)

            verify(exactly = 1) { sender.invoke(123L, 1, "msg1") }
            verify(exactly = 1) { sender.invoke(123L, 2, "msg2") }
        }

    @Test
    fun `call is sent when minimum interval has elapsed`() =
        runTest(UnconfinedTestDispatcher()) {
            var fakeNow =
                kotlin.time.Clock.System
                    .now()
            val throttler =
                DraftThrottler(
                    minInterval = 100.milliseconds,
                    clock = { fakeNow },
                )
            val sender = makeSender()

            throttler.throttle(backgroundScope, messageId = 1, chatId = 123L, newText = "first", sender = sender)

            fakeNow += 200.milliseconds

            throttler.throttle(backgroundScope, messageId = 1, chatId = 123L, newText = "after-interval", sender = sender)

            verify(exactly = 1) { sender.invoke(123L, 1, "first") }
            verify(exactly = 1) { sender.invoke(123L, 1, "after-interval") }
        }
}
