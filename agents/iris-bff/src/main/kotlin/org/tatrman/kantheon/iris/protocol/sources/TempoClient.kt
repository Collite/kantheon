package org.tatrman.kantheon.iris.protocol.sources

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches the turn's trace from Tempo (OTLP JSON) — the source for the execution
 * section and for `call.purpose`, which the gateway cannot store (PT-24).
 *
 * A 404 is [SourceOutcome.Degraded] rather than an empty success, and the reason
 * says so: with retention now at 336h a missing trace usually means the turn
 * predates a retention change or the span never exported, and a reader must not
 * read "no spans" as "nothing executed".
 */
class TempoClient(
    private val baseUrl: String,
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun fetch(
        traceId: String,
        bearer: String,
    ): SourceOutcome<TempoSource> {
        if (baseUrl.isBlank()) return SourceOutcome.SkippedByConfig()
        if (traceId.isBlank()) return SourceOutcome.Degraded("tempo: turn carries no trace_id")
        return guardSource("tempo") {
            val res: HttpResponse =
                http.get("${baseUrl.trimEnd('/')}/api/traces/$traceId") { bearerAuth(bearer) }
            if (res.status == HttpStatusCode.NotFound) {
                error("trace $traceId not found (expired or never exported)")
            }
            if (!res.status.isSuccess()) error("HTTP ${res.status.value}")
            parse(res.bodyAsText())
        }
    }

    private fun parse(body: String): TempoSource {
        val root = json.parseToJsonElement(body).jsonObject
        val spans = mutableListOf<SpanData>()

        root["batches"]?.jsonArray?.forEach { batch ->
            val b = batch.jsonObject
            val serviceName =
                b["resource"]
                    ?.jsonObject
                    ?.get("attributes")
                    ?.jsonArray
                    ?.firstOrNull { it.jsonObject["key"]?.jsonPrimitive?.content == "service.name" }
                    ?.let { attrValue(it.jsonObject["value"]) }
                    .orEmpty()

            b["scopeSpans"]?.jsonArray?.forEach { scope ->
                scope.jsonObject["spans"]?.jsonArray?.forEach { s ->
                    val span = s.jsonObject
                    val start = span["startTimeUnixNano"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                    val end = span["endTimeUnixNano"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                    spans +=
                        SpanData(
                            spanId = span["spanId"]?.jsonPrimitive?.content.orEmpty(),
                            name = span["name"]?.jsonPrimitive?.content.orEmpty(),
                            serviceName = serviceName,
                            durationMs = ((end - start) / 1_000_000L).coerceAtLeast(0),
                            attributes =
                                span["attributes"]
                                    ?.jsonArray
                                    .orEmpty()
                                    .mapNotNull { a ->
                                        val key = a.jsonObject["key"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                        key to attrValue(a.jsonObject["value"])
                                    }.toMap(),
                        )
                }
            }
        }
        return TempoSource(status = SourceStatus.OK, detail = "${spans.size} span(s)", spans = spans)
    }

    /** OTLP AnyValue — one of stringValue / intValue / boolValue / doubleValue. */
    private fun attrValue(value: kotlinx.serialization.json.JsonElement?): String {
        val v = value?.jsonObject ?: return ""
        return listOf("stringValue", "intValue", "boolValue", "doubleValue")
            .firstNotNullOfOrNull { v[it]?.jsonPrimitive?.content }
            .orEmpty()
    }
}
