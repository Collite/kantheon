package org.tatrman.kantheon.pythia.orchestrator

import org.tatrman.kantheon.pythia.events.NatsPublisher
import java.util.concurrent.CopyOnWriteArrayList

/** Records every publish (subject + payload) for assertions. */
class RecordingNatsPublisher : NatsPublisher {
    val published = CopyOnWriteArrayList<Pair<String, ByteArray>>()

    override val connected: Boolean = true

    override fun publish(
        subject: String,
        payload: ByteArray,
    ) {
        published.add(subject to payload)
    }

    fun subjects(): List<String> = published.map { it.first }
}

/** Always throws — proves the emitter degrades without propagating. */
class ThrowingNatsPublisher : NatsPublisher {
    override val connected: Boolean = false

    override fun publish(
        subject: String,
        payload: ByteArray,
    ): Unit = throw RuntimeException("nats down")
}
