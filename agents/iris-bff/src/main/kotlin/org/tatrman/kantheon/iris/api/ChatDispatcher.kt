package org.tatrman.kantheon.iris.api

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.kantheon.common.v1.AgentId
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.iris.audit.AuditStore
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.AgentResume
import org.tatrman.kantheon.iris.dispatch.AgentTurn
import org.tatrman.kantheon.iris.domain.NewTurn
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.routing.HandoffAssembler
import org.tatrman.kantheon.iris.routing.RoutingEnvelopes
import org.tatrman.kantheon.iris.routing.ThemisAuthException
import org.tatrman.kantheon.iris.routing.ThemisClient
import org.tatrman.kantheon.iris.routing.ThemisUnavailableException
import org.tatrman.kantheon.iris.stream.TurnOutcome
import org.tatrman.kantheon.iris.v1.DoneEvent
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.themis.v1.Themis.AwaitingClarification
import org.tatrman.kantheon.themis.v1.Themis.Decomposition
import org.tatrman.kantheon.themis.v1.Themis.FreshQuestion
import org.tatrman.kantheon.themis.v1.Themis.Profile
import org.tatrman.kantheon.themis.v1.Themis.RefusalWithGaps
import org.tatrman.kantheon.themis.v1.Themis.ResolveContext
import org.tatrman.kantheon.themis.v1.Themis.ResolveRequest
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse
import org.tatrman.kantheon.themis.v1.Themis.Resolution
import org.tatrman.kantheon.themis.v1.Themis.RoutingDecision
import java.time.Instant
import java.util.UUID

/**
 * Owns a turn's lifecycle with the Phase-3 routing layer (Stage 3.1). Every turn
 * **resolves through Themis before dispatch**: `themis.understand()` is called
 * with the user's question, profile `CHAT_QUICK`, the assembled [HandoffContext]
 * (PD-1), and an optional `routing_hint` (RoutingPickChip re-issue). The
 * [ResolveResponse] outcome drives the branch:
 *
 *  - **Resolution** → dispatch to `routing.chosen_agent_id` via [AgentDispatcher]
 *    (or, on `needs_user_pick`, emit RoutingPickChips and stop — no agent call).
 *  - **AwaitingClarification(multi_question)** → PD-13: SPLIT emits decomposition
 *    PromptChips (no dispatch); KEEP_TOGETHER proceeds whole + a rationale hint.
 *  - **RefusalWithGaps** → an error envelope (e.g. `NO_ENTITLED_AGENT`); no call.
 *
 * Transport (SSE) stays in the route; this is the agent-agnostic orchestration.
 * It persists the turn + writes the audit row (carrying the `RoutingDecision` on
 * the dispatch path) at finalisation.
 */
class ChatDispatcher(
    private val store: SessionStore,
    private val themis: ThemisClient,
    private val dispatcher: AgentDispatcher,
    private val audit: AuditStore,
    private val envelopes: RoutingEnvelopes,
    private val locale: String = "cs",
    private val routerAgentId: String = "themis",
    private val defaultAgentId: String = "golem-v2",
    private val metrics: RoutingMetrics = RoutingMetrics.NOOP,
) {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    /**
     * A per-turn emit wrapper: re-stamps `turn_id` and assigns a single monotone
     * `sequence` across every event of the turn — chip/hint/error envelopes the
     * BFF emits AND the agent's own stream — so a turn that prepends a hint before
     * a dispatch still presents one well-ordered wire to the FE.
     */
    private class Sequenced(
        private val turnId: String,
        private val sink: suspend (IrisStreamEvent) -> Unit,
    ) {
        private var seq = 0L

        suspend fun emit(ev: IrisStreamEvent) {
            sink(
                ev
                    .toBuilder()
                    .setTurnId(turnId)
                    .setSequence(++seq)
                    .build(),
            )
        }

        suspend fun envelope(env: FormatEnvelope) = emit(IrisStreamEvent.newBuilder().setEnvelope(env).build())

        suspend fun done(outcome: String) =
            emit(IrisStreamEvent.newBuilder().setDone(DoneEvent.newBuilder().setOutcome(outcome)).build())
    }

    suspend fun runTurn(
        caller: CallerIdentity,
        sessionId: UUID,
        question: String,
        desiredFormat: String?,
        correlationId: String,
        routingHintAgentId: String? = null,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        val startNanos = System.nanoTime()
        val turnId = UUID.randomUUID()
        val seq = Sequenced(turnId.toString(), emit)
        val session = store.getSession(sessionId) ?: error("session $sessionId not found")
        val previousTurn = store.getTurns(sessionId).lastOrNull()
        val handoff = HandoffAssembler.fromPreviousTurn(session, previousTurn)

        val response =
            try {
                themis.understand(buildResolveRequest(sessionId, question, routingHintAgentId, handoff), caller.bearer)
            } catch (e: ThemisAuthException) {
                // Expired/invalid OBO bearer: fail closed, prompt re-auth, do NOT invite a retry.
                val out =
                    emitError(
                        turnId,
                        seq,
                        caller,
                        sessionId,
                        question,
                        "AUTH_EXPIRED",
                        "Your session has expired; please sign in again.",
                    )
                metrics.recordTurn(out.doneOutcome, System.nanoTime() - startNanos)
                return out
            } catch (e: ThemisUnavailableException) {
                val out =
                    emitError(
                        turnId,
                        seq,
                        caller,
                        sessionId,
                        question,
                        "THEMIS_UNAVAILABLE",
                        "Routing is temporarily unavailable; please retry.",
                    )
                metrics.recordTurn(out.doneOutcome, System.nanoTime() - startNanos)
                return out
            }

        val result =
            when (response.outcomeCase) {
                ResolveResponse.OutcomeCase.RESOLUTION ->
                    handleResolution(
                        turnId,
                        seq,
                        caller,
                        sessionId,
                        question,
                        response.resolution,
                        handoff,
                        desiredFormat,
                        correlationId,
                    )
                ResolveResponse.OutcomeCase.AWAITING ->
                    handleAwaiting(
                        turnId,
                        seq,
                        caller,
                        sessionId,
                        question,
                        response.awaiting,
                        handoff,
                        desiredFormat,
                        correlationId,
                    )
                ResolveResponse.OutcomeCase.REFUSAL ->
                    handleRefusal(turnId, seq, caller, sessionId, question, response.refusal)
                else ->
                    emitError(turnId, seq, caller, sessionId, question, "NO_OUTCOME", "Themis returned no outcome.")
            }
        metrics.recordTurn(result.doneOutcome, System.nanoTime() - startNanos)
        return result
    }

    private suspend fun handleResolution(
        turnId: UUID,
        seq: Sequenced,
        caller: CallerIdentity,
        sessionId: UUID,
        question: String,
        resolution: Resolution,
        handoff: org.tatrman.kantheon.common.v1.HandoffContext?,
        desiredFormat: String?,
        correlationId: String,
    ): TurnOutcome {
        val routing = if (resolution.hasRouting()) resolution.routing else null

        // Layer-3 ambiguity: offer the alternates as RoutingPickChips, no dispatch.
        // A needs_user_pick with no alternates is a malformed decision — there is
        // nothing to pick, so fall through to the no-decision error rather than
        // emitting a dead-end empty chip bubble.
        if (routing != null && routing.needsUserPick && routing.alternatesList.isNotEmpty()) {
            metrics.recordNeedsUserPick()
            val env = envelopes.routingPick(turnId.toString(), sessionId, routing.alternatesList)
            seq.envelope(env)
            seq.done("done")
            persist(
                turnId,
                sessionId,
                caller,
                question,
                routerAgentId,
                TurnStatus.DONE,
                env,
                null,
                routing = routing,
                alternatesOffered = routing.alternatesList.map { it.agentId.value },
            )
            return TurnOutcome(env, TurnStatus.DONE, null, null, "done")
        }

        val chosen = routing?.chosenAgentId?.value?.takeIf { it.isNotEmpty() }
        if (chosen == null) {
            return emitError(
                turnId,
                seq,
                caller,
                sessionId,
                question,
                "NO_ROUTING_DECISION",
                "Could not determine an agent for this question.",
                routing = routing,
            )
        }
        return dispatchTo(
            chosen,
            turnId,
            seq,
            caller,
            sessionId,
            question,
            handoff,
            desiredFormat,
            correlationId,
            routing,
        )
    }

    private suspend fun handleAwaiting(
        turnId: UUID,
        seq: Sequenced,
        caller: CallerIdentity,
        sessionId: UUID,
        question: String,
        awaiting: AwaitingClarification,
        handoff: org.tatrman.kantheon.common.v1.HandoffContext?,
        desiredFormat: String?,
        correlationId: String,
    ): TurnOutcome {
        if (awaiting.hasMultiQuestion()) {
            val mq = awaiting.multiQuestion
            if (mq.decomposition == Decomposition.SPLIT) {
                val env = envelopes.decomposition(turnId.toString(), sessionId, mq.subQuestionsList)
                seq.envelope(env)
                seq.done("done")
                persist(turnId, sessionId, caller, question, routerAgentId, TurnStatus.DONE, env, null)
                return TurnOutcome(env, TurnStatus.DONE, null, null, "done")
            }
            // KEEP_TOGETHER (and UNSPECIFIED): route the whole question as one
            // cross-domain turn (transitional target), surfacing the rationale.
            if (mq.decompositionRationale.isNotBlank()) {
                seq.envelope(envelopes.hint(turnId.toString(), sessionId, mq.decompositionRationale))
            }
            return dispatchTo(
                defaultAgentId,
                turnId,
                seq,
                caller,
                sessionId,
                question,
                handoff,
                desiredFormat,
                correlationId,
                routing = null,
            )
        }

        // A plain Themis clarification (entity/intent ambiguity) — render the
        // question + option labels as PromptChips; each re-submits as a fresh turn.
        val env =
            envelopes.clarification(
                turnId.toString(),
                sessionId,
                awaiting.question,
                awaiting.optionsList.map { it.label },
            )
        seq.envelope(env)
        seq.done("done")
        persist(turnId, sessionId, caller, question, routerAgentId, TurnStatus.DONE, env, null)
        return TurnOutcome(env, TurnStatus.DONE, null, null, "done")
    }

    private suspend fun handleRefusal(
        turnId: UUID,
        seq: Sequenced,
        caller: CallerIdentity,
        sessionId: UUID,
        question: String,
        refusal: RefusalWithGaps,
    ): TurnOutcome {
        val env = envelopes.refusal(turnId.toString(), sessionId, refusal)
        val code =
            refusal.gapsList
                .firstOrNull()
                ?.kind
                ?.name ?: "REFUSED"
        metrics.recordRefusal(code)
        seq.envelope(env)
        seq.done("failed")
        persist(turnId, sessionId, caller, question, routerAgentId, TurnStatus.FAILED, env, code)
        return TurnOutcome(env, TurnStatus.FAILED, null, code, "failed")
    }

    private suspend fun dispatchTo(
        chosenAgentId: String,
        turnId: UUID,
        seq: Sequenced,
        caller: CallerIdentity,
        sessionId: UUID,
        question: String,
        handoff: org.tatrman.kantheon.common.v1.HandoffContext?,
        desiredFormat: String?,
        correlationId: String,
        routing: RoutingDecision?,
    ): TurnOutcome {
        val agentTurn =
            AgentTurn(turnId.toString(), sessionId, caller, correlationId, question, desiredFormat, handoff)
        // PD-4 (T4): the entities the BFF carried in (the previous in-scope context).
        val sentEntities = handoff?.entitiesList?.map { it.entityType to it.entityId }?.toSet() ?: emptySet()
        val sentLabels = handoff?.entitiesList?.joinToString(", ") { it.displayLabel.ifEmpty { it.entityId } } ?: ""
        var appliedContextJson: String? = null

        // Intercept the answer envelope to (a) capture the echoed entity_context for
        // read-back and (b) append a scope-mismatch WARNING when the agent applied a
        // different scope than the one carried in (PD-4 mismatch → warning on the bubble).
        val outcome =
            dispatcher.dispatch(chosenAgentId, agentTurn) { ev ->
                if (ev.hasEnvelope() && ev.envelope.entityContextCount > 0) {
                    appliedContextJson = entityContextJson(ev.envelope)
                    val applied =
                        ev.envelope.entityContextList
                            .map { it.entityType to it.entityId }
                            .toSet()
                    if (sentEntities.isNotEmpty() && applied != sentEntities) {
                        seq.emit(
                            ev.toBuilder().setEnvelope(withScopeWarning(ev.envelope, sentLabels)).build(),
                        )
                        return@dispatch
                    }
                }
                seq.emit(ev)
            }
        // EntityContext read-back: the next turn's handoff/excerpt carry these.
        appliedContextJson?.let { store.setEntityContext(sessionId, it) }
        persist(
            turnId,
            sessionId,
            caller,
            question,
            chosenAgentId,
            outcome.status,
            outcome.envelope,
            outcome.errorCode,
            routing = routing,
            resumeIssuerAgentId = if (outcome.status == TurnStatus.CLARIFICATION) chosenAgentId else null,
            pendingResumeToken = outcome.pendingResumeToken,
        )
        return outcome
    }

    suspend fun runResume(
        caller: CallerIdentity,
        sessionId: UUID,
        req: ChatResumeRequestDto,
        correlationId: String,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        val turnId = UUID.randomUUID()
        val seq = Sequenced(turnId.toString(), emit)
        val question = req.freeTextAnswer ?: "[resume:${req.selectedOptionId ?: req.resumeToken}]"
        // Fail closed: a token that matches no open clarification (already consumed,
        // replayed, or forged) is rejected, never defaulted to an agent dispatch.
        val issuer =
            resumeIssuer(sessionId, req.resumeToken)
                ?: return emitError(
                    turnId,
                    seq,
                    caller,
                    sessionId,
                    question,
                    "NO_OPEN_CLARIFICATION",
                    "This clarification is no longer open.",
                )
        val outcome =
            dispatcher.resume(
                issuer,
                AgentResume(
                    turnId.toString(),
                    sessionId,
                    caller,
                    correlationId,
                    req.resumeToken,
                    req.selectedOptionId,
                    req.freeTextAnswer,
                ),
            ) { seq.emit(it) }
        persist(
            turnId,
            sessionId,
            caller,
            question,
            issuer,
            outcome.status,
            outcome.envelope,
            outcome.errorCode,
            resumeIssuerAgentId = if (outcome.status == TurnStatus.CLARIFICATION) issuer else null,
            pendingResumeToken = outcome.pendingResumeToken,
        )
        // The clarification is consumed once resolved; clear the token so it can't
        // be replayed. A FAILED resume keeps it open.
        if (outcome.status != TurnStatus.FAILED) {
            store.clearPendingResumeToken(sessionId, req.resumeToken)
        }
        return outcome
    }

    /** Find the open clarification turn matching the resume token (issuer routing). */
    fun resumeIssuer(
        sessionId: UUID,
        resumeToken: String,
    ): String? =
        store
            .getTurns(sessionId, includeDiscarded = true)
            .firstOrNull { it.pendingResumeToken == resumeToken }
            ?.resumeIssuerAgentId

    private suspend fun emitError(
        turnId: UUID,
        seq: Sequenced,
        caller: CallerIdentity,
        sessionId: UUID,
        question: String,
        code: String,
        message: String,
        routing: RoutingDecision? = null,
    ): TurnOutcome {
        val env = envelopes.error(turnId.toString(), sessionId, code, message)
        seq.envelope(env)
        seq.done("failed")
        persist(turnId, sessionId, caller, question, routerAgentId, TurnStatus.FAILED, env, code, routing = routing)
        return TurnOutcome(env, TurnStatus.FAILED, null, code, "failed")
    }

    /** Serialise the envelope's echoed entity_context into the session-stored shape
     *  the [HandoffAssembler] reads back (snake_case entity_type/entity_id/display_label). */
    private fun entityContextJson(env: FormatEnvelope): String =
        env.entityContextList.joinToString(prefix = "[", postfix = "]", separator = ",") { e ->
            buildJsonObject {
                put("entity_type", JsonPrimitive(e.entityType))
                if (e.hasEntityId()) put("entity_id", JsonPrimitive(e.entityId))
                put("display_label", JsonPrimitive(e.displayLabel))
            }.toString()
        }

    /** PD-4: append a WARNING ResponseMessage to the answer envelope when the agent
     *  applied a different entity scope than the one the BFF carried in. */
    private fun withScopeWarning(
        env: FormatEnvelope,
        sentLabels: String,
    ): FormatEnvelope {
        val appliedLabels = env.entityContextList.joinToString(", ") { it.displayLabel }
        return env
            .toBuilder()
            .addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.WARNING)
                    .setCode("scope_changed")
                    .setHumanMessage(
                        "Rozsah se změnil: odpověď platí pro „$appliedLabels“, " +
                            "ale předchozí kontext byl „$sentLabels“.",
                    ).build(),
            ).build()
    }

    private fun buildResolveRequest(
        sessionId: UUID,
        question: String,
        routingHintAgentId: String?,
        handoff: org.tatrman.kantheon.common.v1.HandoffContext?,
    ): ResolveRequest {
        val builder =
            ResolveRequest
                .newBuilder()
                .setConversationId(sessionId.toString())
                .setFresh(FreshQuestion.newBuilder().setConversationId(sessionId.toString()).setText(question))
                .setProfile(Profile.CHAT_QUICK)
                .setContext(ResolveContext.newBuilder().setLocale(locale))
        if (!routingHintAgentId.isNullOrBlank()) {
            builder.routingHint = AgentId.newBuilder().setValue(routingHintAgentId).build()
        }
        if (handoff != null) builder.priorContext = handoff
        return builder.build()
    }

    @Suppress("LongParameterList")
    private fun persist(
        turnId: UUID,
        sessionId: UUID,
        caller: CallerIdentity,
        question: String,
        agentId: String,
        status: TurnStatus,
        envelope: FormatEnvelope?,
        errorCode: String?,
        routing: RoutingDecision? = null,
        alternatesOffered: List<String> = emptyList(),
        resumeIssuerAgentId: String? = null,
        pendingResumeToken: String? = null,
    ) {
        val envelopeJson = envelope?.let { printer.print(it) }
        store.appendTurn(
            NewTurn(
                sessionId = sessionId,
                turnId = turnId,
                agentId = agentId,
                question = question,
                status = status,
                envelopeJson = envelopeJson,
                displayedBlockIds = envelope?.bubbleId?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList(),
                alternatesOffered = alternatesOffered,
                pendingResumeToken = pendingResumeToken,
                resumeIssuerAgentId = resumeIssuerAgentId,
            ),
        )
        audit.append(
            userId = caller.userId,
            eventKind = "turn",
            payloadJson =
                buildJsonObject {
                    put("question", JsonPrimitive(question))
                    put("agentId", JsonPrimitive(agentId))
                    put("status", JsonPrimitive(status.wire))
                    errorCode?.let { put("errorCode", JsonPrimitive(it)) }
                    if (routing != null) {
                        put("routingChosenAgentId", JsonPrimitive(routing.chosenAgentId.value))
                        put("routingLayerHit", JsonPrimitive(routing.layerHit))
                        put("routingConfidence", JsonPrimitive(routing.confidence))
                        put("routingNeedsUserPick", JsonPrimitive(routing.needsUserPick))
                    }
                }.toString(),
            ts = Instant.now(),
        )
    }
}
