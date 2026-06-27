package org.tatrman.kantheon.hebe.observability

import ch.qos.logback.classic.Level
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ObserverEvent
import org.tatrman.kantheon.hebe.api.Span
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.MDC

@Suppress("detekt:all")
class LogbackObserver(
    private val ringBuffer: LogRingBuffer = LogRingBuffer(1000),
) : Observer {
    private val logger = KotlinLogging.logger("org.tatrman.kantheon.hebe.observability")
    private val activeSpans = ConcurrentHashMap<String, OTelSpan>()

    override fun event(e: ObserverEvent) {
        val baseLevel =
            when (e) {
                is ObserverEvent.TurnStart -> Level.INFO
                is ObserverEvent.TurnEnd -> Level.INFO
                is ObserverEvent.ToolDispatched -> if (e.ok) Level.INFO else Level.ERROR
                is ObserverEvent.LlmCall -> Level.INFO
                is ObserverEvent.ApprovalRequested -> Level.INFO
                is ObserverEvent.ApprovalResolved -> Level.INFO
                is ObserverEvent.MemoryDbReady -> Level.INFO
                is ObserverEvent.PluginLoaded -> Level.INFO
                is ObserverEvent.LeakDetected -> Level.WARN
            }

        val sessionId: String? =
            when (e) {
                is ObserverEvent.TurnStart -> e.sessionId
                is ObserverEvent.TurnEnd -> e.sessionId
                else -> null
            }
        val turnId: String? =
            when (e) {
                is ObserverEvent.TurnStart -> e.turnId
                is ObserverEvent.TurnEnd -> e.turnId
                is ObserverEvent.ToolDispatched -> e.turnId
                is ObserverEvent.LlmCall -> e.turnId
                is ObserverEvent.ApprovalRequested -> e.turnId
                is ObserverEvent.ApprovalResolved -> e.turnId
                else -> null
            }
        val tool: String? =
            when (e) {
                is ObserverEvent.ToolDispatched -> e.tool
                is ObserverEvent.ApprovalRequested -> e.tool
                else -> null
            }
        val pluginId: String? =
            when (e) {
                is ObserverEvent.PluginLoaded -> e.pluginId
                else -> null
            }
        val channel: String? =
            when (e) {
                is ObserverEvent.TurnStart -> e.channel
                is ObserverEvent.TurnEnd -> e.channel
                else -> null
            }

        val eventLog =
            LogEvent(
                timestamp = Instant.now(),
                level = fromLevel(baseLevel),
                logger = "org.tatrman.kantheon.hebe.observability.Observer",
                message = e.toString(),
                sessionId = sessionId,
                turnId = turnId,
                tool = tool,
                pluginId = pluginId,
                channel = channel,
            )
        ringBuffer.writeLog(eventLog)

        val previousMdc = MDC.getCopyOfContextMap() ?: emptyMap()
        try {
            MDC.put("ts", Instant.now().toEpochMilli().toString())
            MDC.put("level", baseLevel.toString())
            MDC.put("logger", "org.tatrman.kantheon.hebe.observability.Observer")
            sessionId?.let { MDC.put("sessionId", it) }
            turnId?.let { MDC.put("turnId", it) }
            tool?.let { MDC.put("tool", it) }
            pluginId?.let { MDC.put("pluginId", it) }
            channel?.let { MDC.put("channel", it) }

            val msg = e.toString()
            when (baseLevel) {
                Level.TRACE -> logger.trace { msg }
                Level.DEBUG -> logger.debug { msg }
                Level.INFO -> logger.info { msg }
                Level.WARN -> logger.warn { msg }
                Level.ERROR -> logger.error { msg }
            }
        } finally {
            MDC.setContextMap(previousMdc)
        }
    }

    override fun span(
        name: String,
        attrs: Map<String, Any>,
    ): Span {
        val spanId = "span-${System.nanoTime()}"
        val span = OTelSpan(name, spanId, attrs)
        activeSpans[spanId] = span
        val event =
            LogEvent(
                timestamp = Instant.now(),
                level = LogLevel.TRACE,
                logger = "org.tatrman.kantheon.hebe.observability.Span",
                message = "Span started: $name [$spanId]",
            )
        ringBuffer.writeLog(event)
        logger.trace { "Span started: $name [$spanId] attrs=$attrs" }
        return span
    }

    private inner class OTelSpan(
        private val name: String,
        private val spanId: String,
        private val attrs: Map<String, Any>,
    ) : Span {
        private var isRecording = true
        private val spanAttrs = attrs.toMutableMap()
        private var startTime = Instant.now()

        override fun setAttribute(
            key: String,
            value: Any,
        ) {
            spanAttrs[key] = value
        }

        override fun recordError(t: Throwable) {
            if (isRecording) {
                val event =
                    LogEvent(
                        timestamp = Instant.now(),
                        level = LogLevel.ERROR,
                        logger = "org.tatrman.kantheon.hebe.observability.Span",
                        message = "Span error: $name [$spanId] - ${t.message}",
                        throwable = t,
                    )
                ringBuffer.writeLog(event)
                logger.error(t) { "Span error: $name [$spanId]" }
            }
        }

        override fun close() {
            if (isRecording) {
                isRecording = false
                activeSpans.remove(spanId)
                val duration = java.time.Duration.between(startTime, Instant.now())
                val event =
                    LogEvent(
                        timestamp = Instant.now(),
                        level = LogLevel.DEBUG,
                        logger = "org.tatrman.kantheon.hebe.observability.Span",
                        message = "Span ended: $name [$spanId] duration=${duration.toMillis()}ms",
                    )
                ringBuffer.writeLog(event)
                logger.debug { "Span ended: $name [$spanId] duration=${duration.toMillis()}ms" }
            }
        }
    }

    fun recentEvents(n: Int = 50): List<LogEvent> = ringBuffer.tail(n)

    fun eventsSince(timestamp: Instant): List<LogEvent> = ringBuffer.since(timestamp)

    companion object {
        fun fromLevel(level: ch.qos.logback.classic.Level): LogLevel =
            when (level) {
                Level.TRACE -> LogLevel.TRACE
                Level.DEBUG -> LogLevel.DEBUG
                Level.INFO -> LogLevel.INFO
                Level.WARN -> LogLevel.WARN
                Level.ERROR -> LogLevel.ERROR
                else -> LogLevel.INFO
            }
    }
}
