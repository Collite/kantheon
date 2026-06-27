package org.tatrman.kantheon.hebe.observability

import ch.qos.logback.classic.Level
import java.time.Instant

enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    companion object {
        fun fromLevel(level: Level): LogLevel =
            when (level) {
                Level.TRACE -> TRACE
                Level.DEBUG -> DEBUG
                Level.INFO -> INFO
                Level.WARN -> WARN
                Level.ERROR -> ERROR
                else -> INFO
            }
    }
}

data class LogEvent(
    val timestamp: Instant,
    val level: LogLevel,
    val logger: String,
    val message: String,
    val throwable: Throwable? = null,
    val sessionId: String? = null,
    val turnId: String? = null,
    val tool: String? = null,
    val pluginId: String? = null,
    val channel: String? = null,
)
