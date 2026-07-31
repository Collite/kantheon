package org.tatrman.kantheon.iris.protocol.sources

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Reads the turn's log lines from Loki over LogQL (architecture §2).
 *
 * Correlated by **trace id**, not by service name: the trace is what ties a turn's
 * lines together across services, and enumerating services would both miss any
 * the estate gained since this code was written and hard-code the
 * `service.name` drift already recorded for themis (`resolver-agent`).
 *
 * **Two things here are estate facts, not preferences** — both learned from a live
 * cluster after this client shipped, and neither reproducible against a fixture:
 *
 * 1. **`trace_id` is structured metadata, not a stream label.** The OTLP ingest path
 *    stores a record's TraceId/SpanId as queryable structured metadata, so the
 *    selector `{trace_id="…"}` matches no stream at all — it has to be a stream
 *    selector plus a metadata filter: `{service_name=~".+"} | trace_id="…"`.
 * 2. **A multi-tenant Loki needs `X-Scope-OrgID`.** With `auth_enabled: true` every
 *    query without it is a flat 401, and the tenant id is deployment-specific, so it
 *    is configuration ([lokiTenant]) rather than something this client can derive.
 *
 * Loki wants nanosecond epochs; the record's window is ISO-8601, so it is
 * converted here rather than stored twice.
 */
class LokiClient(
    private val baseUrl: String,
    private val http: HttpClient,
    /** `X-Scope-OrgID`; blank on a single-tenant Loki, where the header is omitted. */
    private val tenant: String = "",
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
                    parameter("query", logQl(traceId))
                    parameter("start", nanos(from))
                    parameter("end", nanos(to))
                    parameter("limit", limit)
                    bearerAuth(bearer)
                    if (tenant.isNotBlank()) header(TENANT_HEADER, tenant)
                }
            if (!res.status.isSuccess()) {
                // 401 here almost always means the tenant header, not the bearer —
                // worth saying so, because the receipt is where an operator will look.
                val hint = if (res.status.value == 401 && tenant.isBlank()) " (no loki-tenant configured)" else ""
                error("HTTP ${res.status.value}$hint")
            }
            parse(res.bodyAsText(), limit, traceId)
        }
    }

    /**
     * `{service_name=~".+"} | trace_id="…"` — a stream selector (Loki requires at least
     * one non-empty matcher) narrowed by the structured-metadata filter that actually
     * carries the trace. The selector is deliberately "any service that names itself"
     * rather than a service list: the point of correlating by trace is not to know the
     * roster in advance.
     */
    internal fun logQl(traceId: String): String = """{service_name=~".+"} | trace_id="$traceId""""

    /** ISO-8601 with offset → nanosecond epoch, Loki's `start`/`end` unit. */
    internal fun nanos(iso: String): String {
        val i = Instant.parse(iso)
        return (i.epochSecond * 1_000_000_000L + i.nano).toString()
    }

    private fun parse(
        body: String,
        limit: Int,
        traceId: String,
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
                            val text = pair[1].jsonPrimitive.content
                            LogLineData(
                                ts = isoFromNanos(pair[0].jsonPrimitive.content),
                                level = levelOf(labels, text),
                                body = text,
                                // The whole result set IS this trace — we filtered on it.
                                // Reading it back off the stream labels returned "" once
                                // trace_id moved to structured metadata, which is not
                                // returned as a label.
                                traceId = traceId,
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
     * The level, preferring what the stream actually declares over what the text looks
     * like. OTLP ingest labels each stream with `severity_text` (and Loki adds its own
     * `detected_level`), which beats scanning the body: a line mentioning the word
     * "ERROR" in prose is not an error, and a stack trace's continuation lines carry no
     * level at all. The body scan stays as the fallback for log paths that set neither.
     *
     * Absent everything the line is INFO — the neutral choice, since guessing ERROR
     * would inflate the errors section with ordinary output.
     */
    private fun levelOf(
        labels: JsonObject?,
        line: String,
    ): String {
        val declared =
            labels?.get("severity_text")?.jsonPrimitive?.content
                ?: labels?.get("detected_level")?.jsonPrimitive?.content
        return declared?.uppercase()?.takeIf { it in LEVELS }
            ?: LEVELS.firstOrNull { line.contains(it, ignoreCase = false) }
            ?: "INFO"
    }

    private companion object {
        val LEVELS = listOf("FATAL", "ERROR", "WARN", "DEBUG", "TRACE", "INFO")
        const val TENANT_HEADER = "X-Scope-OrgID"
    }
}
