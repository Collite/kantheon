package org.tatrman.kantheon.pythia.resolve

import org.tatrman.kantheon.common.v1.EntityBinding
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.IntentKind
import org.tatrman.kantheon.pythia.v1.ResolutionResult
import org.tatrman.kantheon.pythia.v1.ResolvedIntent
import org.tatrman.kantheon.themis.v1.Themis

/** The typed anchor seeded from `Investigation.context.handoff` (PD-1). */
data class HandoffAnchor(
    val sourceTurnRef: String,
    val queryRef: String,
    val argsJson: String,
    val sql: String,
    val entities: List<EntityBinding>,
) {
    val isPresent: Boolean get() = sourceTurnRef.isNotBlank() || queryRef.isNotBlank() || sql.isNotBlank()
}

/** Resolution outcome (design §3.1 / §3.5 disambiguation policy). */
sealed interface ResolveOutcome {
    data class Resolved(
        val resolution: ResolutionResult,
        val anchor: HandoffAnchor,
    ) : ResolveOutcome

    data class Clarify(
        val question: String,
        val options: List<String>,
    ) : ResolveOutcome

    data class Refuse(
        val gaps: List<String>,
    ) : ResolveOutcome
}

/**
 * Resolves an investigation: seeds the anchor from the typed handoff (PD-1), calls
 * Themis with profile INVESTIGATION_DEEP threading `prior_context`, and maps the
 * three Themis outcomes. The OBO bearer is forwarded by the [ThemisClient].
 */
class Resolver(
    private val themis: ThemisClient,
) {
    /** PD-1: build the anchor from `handoff.view` + entities + source_turn_ref. */
    fun seedAnchor(investigation: Investigation): HandoffAnchor {
        val handoff = investigation.context.handoff
        val view = handoff.view
        return HandoffAnchor(
            sourceTurnRef = handoff.sourceTurnRef,
            queryRef = view.patternId,
            argsJson = view.argsJson,
            sql = view.sql,
            entities = handoff.entitiesList.toList(),
        )
    }

    suspend fun resolve(
        investigation: Investigation,
        bearer: String,
    ): ResolveOutcome {
        val anchor = seedAnchor(investigation)
        val request = buildRequest(investigation, anchor)
        val response = themis.understand(request, bearer)
        return when (response.outcomeCase) {
            Themis.ResolveResponse.OutcomeCase.RESOLUTION ->
                ResolveOutcome.Resolved(toResolutionResult(response.resolution, investigation, anchor), anchor)
            Themis.ResolveResponse.OutcomeCase.AWAITING ->
                ResolveOutcome.Clarify(
                    response.awaiting.question,
                    response.awaiting.optionsList.map { it.label },
                )
            Themis.ResolveResponse.OutcomeCase.REFUSAL ->
                ResolveOutcome.Refuse(response.refusal.gapsList.map { "${it.kind.name}: ${it.description}" })
            else -> ResolveOutcome.Refuse(listOf("themis returned no outcome"))
        }
    }

    private fun buildRequest(
        investigation: Investigation,
        anchor: HandoffAnchor,
    ): Themis.ResolveRequest {
        val builder =
            Themis.ResolveRequest
                .newBuilder()
                .setConversationId(investigation.caller.correlationId.ifBlank { investigation.id })
                .setFresh(
                    Themis.FreshQuestion
                        .newBuilder()
                        .setConversationId(investigation.id)
                        .setText(investigation.question)
                        .setLocale(investigation.context.locale),
                ).setProfile(Themis.Profile.INVESTIGATION_DEEP)
                .setContext(Themis.ResolveContext.newBuilder().setLocale(investigation.context.locale))
        if (investigation.context.hasHandoff()) {
            builder.priorContext = investigation.context.handoff
        }
        return builder.build()
    }

    private fun toResolutionResult(
        resolution: Themis.Resolution,
        investigation: Investigation,
        anchor: HandoffAnchor,
    ): ResolutionResult {
        val intent =
            ResolvedIntent
                .newBuilder()
                .setKind(mapIntent(resolution.intentKind))
                .setResolvedParamsJson(resolution.argsJson.ifBlank { anchor.argsJson })
                .setExpressedParamsJson(resolution.argsJson)
                // Entities come from the typed handoff anchor (PD-1) — themis bindings are
                // span-based; the anchor carries the resolved domain bindings in scope.
                .addAllEntities(anchor.entities)
        if (anchor.queryRef.isNotBlank()) intent.addRelevantQueryRefs(anchor.queryRef)
        if (resolution.functionId.isNotBlank()) intent.addRelevantQueryRefs(resolution.functionId)
        return ResolutionResult.newBuilder().setResolvedIntent(intent).build()
    }

    private fun mapIntent(kind: Themis.IntentKind): IntentKind =
        when (kind) {
            Themis.IntentKind.PROCEDURAL -> IntentKind.INTENT_PROCEDURAL
            Themis.IntentKind.RCA -> IntentKind.INTENT_RCA
            Themis.IntentKind.FORECAST -> IntentKind.INTENT_FORECAST
            Themis.IntentKind.SIMULATION -> IntentKind.INTENT_SIMULATION
            else -> IntentKind.INTENT_PROCEDURAL
        }
}
