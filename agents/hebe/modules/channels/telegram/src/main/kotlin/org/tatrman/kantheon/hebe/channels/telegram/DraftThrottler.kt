package org.tatrman.kantheon.hebe.channels.telegram

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.telegram.telegrambots.meta.api.objects.message.Message

class DraftThrottler(
    private val minInterval: Duration = Duration.parse("800ms"),
    private val minCharsChanged: Int = 80,
    private val clock: () -> kotlin.time.Instant = { Clock.System.now() },
) {
    private val pending = ConcurrentHashMap<Int, ThrottledEdit>()
    private val mutex = Mutex()

    data class ThrottledEdit(
        val chatId: Long,
        val messageId: Int,
        val text: String,
        val lastSentText: String,
        val lastSentAt: kotlin.time.Instant,
    )

    fun throttle(
        scope: CoroutineScope,
        messageId: Int,
        chatId: Long,
        newText: String,
        sender: EditMessageFunc,
    ) {
        scope.launch {
            mutex.withLock {
                val existing = pending[messageId]
                val now = clock()

                if (existing != null) {
                    val timeSinceLast = now - existing.lastSentAt
                    val charsChanged = newText.length - existing.lastSentText.commonPrefixWith(newText).length

                    if (timeSinceLast < minInterval && charsChanged < minCharsChanged) {
                        pending[messageId] = existing.copy(text = newText)
                        return@launch
                    }
                }

                pending[messageId] =
                    ThrottledEdit(
                        chatId = chatId,
                        messageId = messageId,
                        text = newText,
                        lastSentText = newText,
                        lastSentAt = now,
                    )

                sender(chatId, messageId, newText)
            }
        }
    }

    fun flushAll() {
        pending.clear()
    }
}

typealias EditMessageFunc = (Long, Int, String) -> Message?
