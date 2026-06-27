package org.tatrman.kantheon.iris.routing

import org.tatrman.kantheon.common.v1.AgentId
import org.tatrman.kantheon.themis.v1.Themis.AgentAlternate
import org.tatrman.kantheon.themis.v1.Themis.AwaitingClarification
import org.tatrman.kantheon.themis.v1.Themis.Decomposition
import org.tatrman.kantheon.themis.v1.Themis.Gap
import org.tatrman.kantheon.themis.v1.Themis.GapKind
import org.tatrman.kantheon.themis.v1.Themis.MultiQuestionDetected
import org.tatrman.kantheon.themis.v1.Themis.RefusalWithGaps
import org.tatrman.kantheon.themis.v1.Themis.ResolveRequest
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse
import org.tatrman.kantheon.themis.v1.Themis.Resolution
import org.tatrman.kantheon.themis.v1.Themis.RoutingDecision

/**
 * Scripted [ThemisClient] for unit/component tests. The default responder honours
 * a `routing_hint` as a Layer-0 resolution to that agent (so a RoutingPickChip
 * re-issue lands on the picked agent) and otherwise resolves to [defaultAgent].
 * Pass a custom [responder] to drive the dispatch decision table.
 */
class FakeThemisClient(
    private val defaultAgent: String = "golem-v2",
    private val responder: (ResolveRequest) -> ResolveResponse = { req ->
        if (req.hasRoutingHint() && req.routingHint.value.isNotEmpty()) {
            resolutionTo(req.routingHint.value, layer = 0)
        } else {
            resolutionTo(defaultAgent)
        }
    },
) : ThemisClient {
    val seenRequests = mutableListOf<ResolveRequest>()
    val seenBearers = mutableListOf<String>()

    override suspend fun understand(
        request: ResolveRequest,
        bearer: String,
    ): ResolveResponse {
        seenRequests.add(request)
        seenBearers.add(bearer)
        return responder(request)
    }

    companion object {
        fun resolutionTo(
            agentId: String,
            layer: Int = 1,
            confidence: Double = 0.95,
        ): ResolveResponse =
            ResolveResponse
                .newBuilder()
                .setResolution(
                    Resolution
                        .newBuilder()
                        .setFunctionId("noop")
                        .setRouting(
                            RoutingDecision
                                .newBuilder()
                                .setChosenAgentId(AgentId.newBuilder().setValue(agentId))
                                .setConfidence(confidence)
                                .setLayerHit(layer)
                                .setNeedsUserPick(false),
                        ),
                ).build()

        /** needs_user_pick → Iris renders one RoutingPickChip per (agentId, why). */
        fun needsUserPick(alternates: List<Pair<String, String>>): ResolveResponse =
            ResolveResponse
                .newBuilder()
                .setResolution(
                    Resolution
                        .newBuilder()
                        .setFunctionId("ambiguous")
                        .setRouting(
                            RoutingDecision
                                .newBuilder()
                                .setNeedsUserPick(true)
                                .setLayerHit(3)
                                .addAllAlternates(
                                    alternates.map { (id, why) ->
                                        AgentAlternate
                                            .newBuilder()
                                            .setAgentId(AgentId.newBuilder().setValue(id))
                                            .setWhy(why)
                                            .build()
                                    },
                                ),
                        ),
                ).build()

        fun multiQuestion(
            subQuestions: List<String>,
            decomposition: Decomposition,
            rationale: String = "",
        ): ResolveResponse =
            ResolveResponse
                .newBuilder()
                .setAwaiting(
                    AwaitingClarification
                        .newBuilder()
                        .setQuestion("This looks like multiple questions.")
                        .setMultiQuestion(
                            MultiQuestionDetected
                                .newBuilder()
                                .addAllSubQuestions(subQuestions)
                                .setDecomposition(decomposition)
                                .setDecompositionRationale(rationale),
                        ),
                ).build()

        fun refusal(
            gaps: List<Pair<GapKind, String>>,
            rationale: String = "",
        ): ResolveResponse =
            ResolveResponse
                .newBuilder()
                .setRefusal(
                    RefusalWithGaps
                        .newBuilder()
                        .setRationale(rationale)
                        .addAllGaps(
                            gaps.map { (kind, desc) ->
                                Gap
                                    .newBuilder()
                                    .setKind(kind)
                                    .setDescription(desc)
                                    .build()
                            },
                        ),
                ).build()
    }
}
