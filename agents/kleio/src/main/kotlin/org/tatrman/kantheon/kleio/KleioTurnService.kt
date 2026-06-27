package org.tatrman.kantheon.kleio

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.kleio.graph.KleioStatus
import org.tatrman.kantheon.kleio.graph.KleioStrategy
import org.tatrman.kantheon.kleio.graph.KleioTurnState
import org.tatrman.kantheon.kleio.persistence.KleioTurnRecord
import org.tatrman.kantheon.kleio.persistence.KleioTurnsRepository
import org.tatrman.kantheon.kleio.v1.GroundedResponse
import org.tatrman.kantheon.kleio.v1.KleioRequest
import org.tatrman.kantheon.kleio.v1.ResourceUsage
import org.tatrman.kantheon.kleio.v1.Status

/**
 * Runs a Kleio turn: the grounded strategy → a `kleio_turns` row → a
 * `GroundedResponse`. The retrieval count + token usage feed `ResourceUsage`.
 */
class KleioTurnService(
    private val strategy: KleioStrategy,
    private val turns: KleioTurnsRepository,
    private val k: Int,
    private val minScore: Double,
) {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()
    private val json = Json { ignoreUnknownKeys = true }

    /** Assemble a JSON array from proto-JSON fragments by construction (no hand-framed brackets). */
    private fun sourcesArray(fragments: List<String>): String =
        JsonArray(fragments.map { json.parseToJsonElement(it) }).toString()

    suspend fun answer(
        request: KleioRequest,
        bearer: String?,
    ): GroundedResponse {
        val outcome =
            strategy.run(
                KleioTurnState(
                    requestId = request.id,
                    question = request.question,
                    notebookId = request.notebookId,
                    bearer = bearer,
                    k = k,
                    minScore = minScore,
                ),
            )

        val usage =
            ResourceUsage
                .newBuilder()
                .setTokensIn(outcome.tokensIn.toLong())
                .setTokensOut(outcome.tokensOut.toLong())
                .setRetrievalCount(outcome.sourcesUsed.size)
                .build()

        val response =
            GroundedResponse
                .newBuilder()
                .setId("kleio-${request.id}")
                .setRequestId(request.id)
                .addEnvelopes(outcome.envelope)
                .addAllSourcesUsed(outcome.sourcesUsed)
                .setStatus(outcome.status.toProto())
                .setResourceUsage(usage)
                .build()

        turns.save(
            KleioTurnRecord(
                turnId = request.id,
                sessionId = request.caller.correlationId.ifBlank { request.id },
                notebookId = request.notebookId,
                question = request.question,
                status = outcome.status.name,
                envelopesJson = printer.print(outcome.envelope),
                sourcesUsedJson = sourcesArray(outcome.sourcesUsed.map { printer.print(it) }),
                resourceUsageJson = printer.print(usage),
            ),
        )
        return response
    }

    private fun KleioStatus.toProto(): Status =
        when (this) {
            KleioStatus.DONE -> Status.STATUS_DONE
            KleioStatus.NO_GROUNDING -> Status.STATUS_NO_GROUNDING
            KleioStatus.FAILED -> Status.STATUS_FAILED
        }
}
