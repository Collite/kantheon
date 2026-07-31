package org.tatrman.kantheon.iris.protocol.record

import org.tatrman.kantheon.common.v1.AgentId
import org.tatrman.kantheon.protocol.v1.HintTiming
import org.tatrman.kantheon.protocol.v1.ProtocolHints
import org.tatrman.kantheon.themis.v1.Themis.AwaitingClarification
import org.tatrman.kantheon.themis.v1.Themis.Gap
import org.tatrman.kantheon.themis.v1.Themis.GapKind
import org.tatrman.kantheon.themis.v1.Themis.RefusalWithGaps
import org.tatrman.kantheon.themis.v1.Themis.Resolution
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse
import org.tatrman.kantheon.themis.v1.Themis.RoutingDecision
import java.time.Instant
import java.util.UUID

/** Canned Themis outcomes + agent hints shared by the recorder specs. */
internal object RecorderFixtures {
    fun resolution(): ResolveResponse =
        ResolveResponse
            .newBuilder()
            .setResolution(
                Resolution
                    .newBuilder()
                    .setRouting(
                        RoutingDecision
                            .newBuilder()
                            .setChosenAgentId(AgentId.newBuilder().setValue("golem-finance"))
                            .setConfidence(0.94)
                            .setLayerHit(2),
                    ),
            ).build()

    fun awaiting(): ResolveResponse =
        ResolveResponse
            .newBuilder()
            .setAwaiting(AwaitingClarification.newBuilder().setQuestion("Which period did you mean?"))
            .build()

    fun refusal(): ResolveResponse =
        ResolveResponse
            .newBuilder()
            .setRefusal(
                RefusalWithGaps
                    .newBuilder()
                    .addGaps(Gap.newBuilder().setKind(GapKind.NO_ENTITLED_AGENT).setDescription("no entitled agent"))
                    .setRationale("caller holds no role for any agent covering this area"),
            ).build()

    fun hints(): ProtocolHints =
        ProtocolHints
            .newBuilder()
            .addPlanIds("plan-7")
            .addLlmCallRefs("gw-771")
            .addLlmCallRefs("gw-772")
            .setSqlInline("SELECT 1")
            .addTimings(HintTiming.newBuilder().setStep("resolve").setDurationMs(120))
            .addTimings(HintTiming.newBuilder().setStep("execute").setDurationMs(880))
            .build()
}

internal fun turnContext(
    turnId: UUID = UUID.randomUUID(),
    resolveResponse: ResolveResponse? = RecorderFixtures.resolution(),
    hints: ProtocolHints? = RecorderFixtures.hints(),
    correlationId: String? = "corr-1",
    startedAt: Instant = Instant.parse("2026-07-30T09:00:05Z"),
    completedAt: Instant = Instant.parse("2026-07-30T09:00:11Z"),
): TurnRecordContext =
    TurnRecordContext(
        turnId = turnId,
        startedAt = startedAt,
        completedAt = completedAt,
        resolveResponse = resolveResponse,
        hints = hints,
        correlationId = correlationId,
    )
