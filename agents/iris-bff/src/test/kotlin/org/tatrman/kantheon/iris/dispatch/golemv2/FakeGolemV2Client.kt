package org.tatrman.kantheon.iris.dispatch.golemv2

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Loads an SSE fixture from the classpath (`/v2-sse/<name>`). */
fun sseFixture(name: String): String = FakeGolemV2Client::class.java.getResource("/v2-sse/$name")!!.readText()

/**
 * Scripted [GolemV2Client] for unit/component tests. `chatStream` replays the
 * parsed events of a fixture (chosen per question, default `happy-table.sse`);
 * `resume` emits a single resolved envelope.
 */
class FakeGolemV2Client(
    private val fixtureForQuestion: (String) -> String = { "happy-table.sse" },
    private val resumeEnvelopeJson: String = DEFAULT_RESUME_ENVELOPE,
    private val reissueEnvelopeJson: String = DEFAULT_REISSUE_ENVELOPE,
) : GolemV2Client {
    val createdThreads = mutableListOf<String>()

    /** Every bearer the BFF forwarded downstream (OBO-forwarding assertions). */
    val receivedBearers = mutableListOf<String>()

    override suspend fun createSession(
        threadId: String,
        userId: String,
        correlationId: String,
        bearer: String,
        locale: String,
    ): V2SessionStartResponse {
        receivedBearers.add(bearer)
        createdThreads.add(threadId)
        return V2SessionStartResponse(
            thread_id = threadId,
            packages = listOf("erp", "sklad"),
            static_chips = listOf(V2StaticChip("Zobraz faktury", "Zobraz faktury zákazníka")),
            example_questions = listOf("Kolik jsme prodali minulý měsíc?"),
            agent_version = "golem-v2@fake",
        )
    }

    override fun chatStream(
        req: V2ChatRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): Flow<V2StreamEvent> {
        receivedBearers.add(bearer)
        return V2SseParser.parse(sseFixture(fixtureForQuestion(req.user_text))).asFlow()
    }

    /** Every reissueAction the BFF sent downstream (refetch/drilldown assertions). */
    val reissuedActions = mutableListOf<V2ActionRequest>()

    override fun reissueAction(
        req: V2ActionRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): Flow<V2StreamEvent> {
        receivedBearers.add(bearer)
        reissuedActions.add(req)
        val obj = Json.parseToJsonElement(reissueEnvelopeJson).jsonObject
        return listOf<V2StreamEvent>(V2StreamEvent.Envelope(obj)).asFlow()
    }

    override fun resume(
        req: V2ResumeRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): Flow<V2StreamEvent> {
        receivedBearers.add(bearer)
        val obj = Json.parseToJsonElement(resumeEnvelopeJson).jsonObject
        return listOf<V2StreamEvent>(V2StreamEvent.Envelope(obj)).asFlow()
    }

    override suspend fun refresh(
        req: V2RefreshRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): V2RefreshResponse {
        receivedBearers.add(bearer)
        return V2RefreshResponse(
            results = listOf(V2RefreshResultItem(service = req.service ?: "all", status = "ok")),
        )
    }

    companion object {
        const val DEFAULT_RESUME_ENVELOPE =
            """{"bubble_id":"b-r","turn_id":"v2t-r","thread_id":"s-1","text":"Vybráno: Kaufland ČR v.o.s.",""" +
                """"format":{"kind":"plaintext"},"plan_source":"clarification","plan_score":1.0,""" +
                """"created_at":"2026-06-17T09:08:00Z","agent_version":"golem-v2@fake"}"""

        // A refetched table (drilldown / widened filter) — a NEW bubble id (b-drill).
        const val DEFAULT_REISSUE_ENVELOPE =
            """{"bubble_id":"b-drill","turn_id":"v2t-d","thread_id":"s-1",""" +
                """"content":[{"m":"2026-01","r":120},{"m":"2026-02","r":98},{"m":"2026-03","r":140}],""" +
                """"format":{"kind":"table","table":{"headers":[{"name":"m","title":"Měsíc"}]}},""" +
                """"plan_source":"drill","plan_score":1.0,""" +
                """"created_at":"2026-06-17T09:09:00Z","agent_version":"golem-v2@fake"}"""
    }
}
