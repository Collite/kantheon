package org.tatrman.kantheon.iris.protocol.sources

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Reads the turn's log lines from Loki over LogQL (architecture §2).
 *
 * Queried by **trace id**, not by service name: the trace is what ties a turn's
 * lines together across services, and enumerating services would both miss any
 * the estate gained since this code was written and hard-code the
 * `service.name` drift already recorded for themis (`resolver-agent`).
 *
 * Loki wants nanosecond epochs; the record's window is ISO-8601, so it is
 * converted here rather than stored twice.
 */
class LokiClient(
    private val baseUrl: String,
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun fetch(
        traceId: String,
        from: String,
        to: String,
        limit: Int,
        bearer: String,
    ): SourceOutcome<LokiSource> {
        if (baseUrl.isBlank()) return SourceOutcome.SkippedByConfig
        if (traceId.isBlank()) {
            // Without a trace id there is nothing to correlate on, and a bare time
            // window would return every service's lines for every user in that second.
            return SourceOutcome.Degraded("loki: turn carries no trace_id — cannot correlate log lines")
        }
        return guardSource("loki") {
            val res: HttpResponse =
                http.get("${baseUrl.trimEnd('/')}/loki/api/v1/query_range") {
                    parameter("query", """{trace_id="$traceId"}""")
                    parameter("start", nanos(from))
                    parameter("end", nanos(to))
                    parameter("limit", limit)
                    bearerAuth(bearer)
                }
            if (!res.status.isSuccess()) error("HTTP ${res.status.value}")
            parse(res.bodyAsText(), limit)
        }
    }

    /** ISO-8601 with offset → nanosecond epoch, Loki's `start`/`end` unit. */
    internal fun nanos(iso: String): String {
        val i = Instant.parse(iso)
        return (i.epochSecond * 1_000_000_000L + i.nano).toString()
    }

    private fun parse(
        body: String,
        limit: Int,
    ): LokiSource {
        val streams =
            json
                .parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonObject
                ?.get("result")
                ?.jsonArray
                ?: return LokiSource(status = SourceStatus.OK, detail = "0 line(s)", groups = emptyList())

        val groups =
            streams.map { stream ->
                val labels = stream.jsonObject["stream"]?.jsonObject
                val service =
                    labels?.get("service_name")?.jsonPrimitive?.content
                        ?: labels?.get("service")?.jsonPrimitive?.content
                        ?: "unknown"
                val values = stream.jsonObject["values"]?.jsonArray.orEmpty()
                val kept = values.take(limit)
                LogGroup(
                    serviceName = service,
                    // Loki entries are [<ns epoch string>, <line>].
                    lines =
                        kept.map { entry ->
                            val pair = entry.jsonArray
                            LogLineData(
                                ts = isoFromNanos(pair[0].jsonPrimitive.content),
                                level = levelOf(pair[1].jsonPrimitive.content),
                                body = pair[1].jsonPrimitive.content,
                                traceId =
                                    labels
                                        ?.get("trace_id")
                                        ?.jsonPrimitive
                                        ?.content
                                        .orEmpty(),
                            )
                        },
                    droppedByCap = (values.size - kept.size).coerceAtLeast(0),
                )
            }
        return LokiSource(
            status = SourceStatus.OK,
            detail = "${groups.sumOf { it.lines.size }} line(s)",
            groups = groups,
        )
    }

    private fun isoFromNanos(ns: String): String {
        val n = ns.toLongOrNull() ?: return ""
        return Instant.ofEpochSecond(n / 1_000_000_000L, n % 1_000_000_000L).toString()
    }

    /**
     * Loki carries no level label by default; it is recovered from the line so the
     * summary-verbosity filter has something to work with. Absent a match the line
     * is INFO — the neutral choice, since guessing ERROR would inflate the errors
     * section with ordinary output.
     */
    private fun levelOf(line: String): String = LEVELS.firstOrNull { line.contains(it, ignoreCase = false) } ?: "INFO"

    private companion object {
        val LEVELS = listOf("FATAL", "ERROR", "WARN", "DEBUG", "TRACE", "INFO")
    }
}
