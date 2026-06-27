package org.tatrman.kantheon.hebe.observability

import java.time.Instant

open class RingBuffer<T>(
    private val capacity: Int,
) {
    private val buffer = arrayOfNulls<Slot<T>>(capacity)
    private var head = 0
    private var count = 0

    fun write(
        item: T,
        timestamp: Instant = Instant.now(),
    ) {
        buffer[head] = Slot(item, timestamp)
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    fun read(): Sequence<T> {
        if (count == 0) return emptySequence()
        val start = if (count < capacity) 0 else head
        return sequence {
            for (i in 0 until count) {
                val index = (start + i) % capacity
                buffer[index]?.let { yield(it.value) }
            }
        }
    }

    fun readSince(
        since: Instant,
        timestampOf: (T) -> Instant,
    ): Sequence<T> = read().filter { timestampOf(it) >= since }

    fun clear() {
        buffer.fill(null)
        head = 0
        count = 0
    }

    val size: Int get() = count

    private data class Slot<T>(
        val value: T,
        val timestamp: Instant,
    )
}

data class LogSlot(
    val event: LogEvent,
    val index: Long,
)

class LogRingBuffer(
    capacity: Int,
) : RingBuffer<LogEvent>(capacity) {
    private var totalWritten = 0L

    fun writeLog(event: LogEvent): LogSlot {
        write(event)
        val index = totalWritten++
        return LogSlot(event, index)
    }

    fun tail(n: Int): List<LogEvent> = read().toList().takeLast(n)

    fun since(timestamp: Instant): List<LogEvent> = readSince(timestamp) { it.timestamp }.toList()
}
