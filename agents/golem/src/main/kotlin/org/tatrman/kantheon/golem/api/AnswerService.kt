package org.tatrman.kantheon.golem.api

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import org.tatrman.kantheon.common.v1.EntityBinding
import org.tatrman.kantheon.golem.context.PackageContext
import org.tatrman.kantheon.golem.execution.PriorViewResolver
import org.tatrman.kantheon.golem.graph.GolemGraphDeps
import org.tatrman.kantheon.golem.graph.GolemTurnState
import org.tatrman.kantheon.golem.graph.TurnOutcome
import org.tatrman.kantheon.golem.graph.runGolemGraph
import org.tatrman.kantheon.golem.persistence.GolemTurnRecord
import org.tatrman.kantheon.golem.persistence.GolemTurnStatus
import org.tatrman.kantheon.golem.persistence.TurnsRepository
import org.tatrman.kantheon.golem.resume.ParamFill
import org.tatrman.kantheon.golem.resume.ResumeCodec
import org.tatrman.kantheon.golem.resume.ResumeOption
import org.tatrman.kantheon.golem.resume.ResumePayload
import org.tatrman.kantheon.golem.resume.ResumeTokenException
import org.tatrman.kantheon.golem.v1.ConversationalResponse
import org.tatrman.kantheon.golem.v1.GolemContext
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.Status
import java.time.Instant
import java.util.UUID

/** Proto ↔ JSON (proto3-JSON, Rule-7) — the wire codec for the REST surface + persistence. */
object ProtoJson {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()
    private val parser = JsonFormat.parser().ignoringUnknownFields()

    fun print(message: Message): String = printer.print(message)

    fun printList(messages: List<Message>): String = messages.joinToString(",", "[", "]") { printer.print(it) }

    fun <B : Message.Builder> parseInto(
        json: String,
        builder: B,
    ): B {
        parser.merge(json, builder)
        return builder
    }
}

/**
 * Runs one turn end-to-end (architecture §4): graph (compose → gate → execute |
 * clarify) → assemble [ConversationalResponse] → persist one `golem_turns` row.
 * `/v1/answer/sync` is a thin wrapper over [answer]. The caller's OBO [bearer] is
 * threaded to the executor (theseus-mcp), never a service identity.
 *
 * Wiring/HTTP tests are deferred to GH #32 (Stage 2.4 decision); the graph, executor,
 * composer, gate, and persistence underneath are unit-tested.
 */
class AnswerService(
    private val deps: GolemGraphDeps,
    private val packageContext: PackageContext?,
    private val turns: TurnsRepository,
    // Dev/test-only default secret. Production wiring always injects the configured codec and
    // fails fast on the placeholder in a DB-backed deploy (see Wiring.buildAnswerSurface), so a
    // real pod never signs resume tokens with this key.
    private val resumeCodec: ResumeCodec = ResumeCodec("dev-secret-change-in-production"),
    private val now: () -> Instant = Instant::now,
) {
    private val priorViewResolver = PriorViewResolver(turns)

    suspend fun answer(
        request: GolemRequest,
        caller: AdmittedCaller,
    ): ConversationalResponse {
        val initial =
            GolemTurnState(
                request = request,
                bearer = caller.bearer,
                userId = caller.userId,
                tenantId = caller.tenantId,
                model = packageContext?.currentOrNull(),
                // Scope the AMEND/DRILL prior-view lookup to the caller (H2 — no cross-tenant read).
                priorView = priorViewResolver.resolve(request.context, caller.userId, caller.tenantId),
            )
        val finalState = runGolemGraph(initial, deps)
        // One timestamp for the whole turn — the response's finalised_at and the
        // persisted row's created_at/finalised_at agree.
        val finalisedAt = now()
        val responseId = UUID.randomUUID().toString()
        // A required pattern param went unbound → ask the user (param_fill, Δ2) instead of answering.
        val response =
            finalState.execution?.paramFill?.let { pf ->
                paramFillResponse(finalState, caller, pf, responseId, finalisedAt.toString())
            } ?: assembleResponse(finalState, responseId, finalisedAt.toString())
        persistTurn(request, caller, response, finalisedAt)
        return response
    }

    /**
     * Resume a clarification (`POST /v1/resume`). `param_fill` binds [freeTextAnswer] into the
     * partial plan and re-enters at execute (cascade-skip); every other kind splices the chosen
     * option / answer into the original question and re-runs the full turn (the pin-by-id RESUME
     * path is a Themis cross-arc follow-up — see §2 pre-flight).
     */
    suspend fun resume(
        token: String,
        caller: AdmittedCaller,
        freeTextAnswer: String?,
        selectedOptionId: String?,
    ): ConversationalResponse {
        val payload = resumeCodec.decode(token) // throws ResumeTokenException on bad/expired token
        requireSameCaller(payload, caller)
        return when (payload.kind) {
            ParamFill.KIND -> resumeParamFill(payload, caller, freeTextAnswer)
            else -> resumeBySplice(payload, caller, freeTextAnswer, selectedOptionId)
        }
    }

    /**
     * B1 — bind the resume token to its caller. The HMAC proves the token wasn't forged, but a
     * leaked-yet-valid token must not let a *different* admitted user replay another user's turn
     * (it would re-run the victim's question/partial-plan and persist it under the attacker's
     * identity). Reject when the token's minted (userId, tenantId) doesn't match the resuming
     * caller. Treated as a token rejection (→ 400) by the route.
     */
    private fun requireSameCaller(
        payload: ResumePayload,
        caller: AdmittedCaller,
    ) {
        if (payload.tenantId != caller.tenantId || payload.userId != caller.userId) {
            throw ResumeTokenException("resume token was issued for a different caller")
        }
    }

    private suspend fun resumeParamFill(
        payload: ResumePayload,
        caller: AdmittedCaller,
        freeTextAnswer: String?,
    ): ConversationalResponse {
        val answer = freeTextAnswer?.trim().orEmpty()
        val paramName =
            payload.options
                .firstOrNull()
                ?.id
                .orEmpty()
        // M5 — keep the original turn's identity + locale rather than a bare 2-field stub, so the
        // resumed turn's agentId / applied_context / locale survive the round-trip.
        val request =
            GolemRequest
                .newBuilder()
                .setId(payload.turnId)
                .setGolemId(payload.golemId)
                .setQuestion(payload.userText)
                .also { b -> payload.locale.takeIf { it.isNotBlank() }?.let { b.contextBuilder.locale = it } }
                .build()
        val boundPlan = ParamFill.bindParamFill(payload.pickedPlanJson, paramName, answer)
        val initial =
            GolemTurnState(
                request = request,
                bearer = caller.bearer,
                userId = caller.userId,
                tenantId = caller.tenantId,
                model = packageContext?.currentOrNull(),
                plan = boundPlan,
                resumeParamFill = true,
            )
        val finalState = runGolemGraph(initial, deps)
        val finalisedAt = now()
        val responseId = UUID.randomUUID().toString()
        val response =
            finalState.execution?.paramFill?.let { pf ->
                paramFillResponse(finalState, caller, pf, responseId, finalisedAt.toString())
            } ?: assembleResponse(finalState, responseId, finalisedAt.toString())
        persistTurn(request, caller, response, finalisedAt)
        return response
    }

    /**
     * Splice the chosen option / free-text into the original question and re-run as a fresh turn.
     *
     * H1 — precedence is **typed option before free text**: the option carries the proto's
     * pin-by-id fields (`entityTypeRef`/`resolvedId`), free text doesn't, so a typed choice must
     * win when present. An explicit `selectedOptionId` that matches no option is rejected (not
     * silently defaulted to the first option). When the chosen option resolved to a concrete
     * entity, that pin is carried forward as an applied handoff binding so the turn pins the
     * entity rather than relying solely on re-resolving the spliced text. (Full Resolver RESUME
     * pinning at the query level is a Themis cross-arc follow-up — see §2 pre-flight.)
     */
    private suspend fun resumeBySplice(
        payload: ResumePayload,
        caller: AdmittedCaller,
        freeTextAnswer: String?,
        selectedOptionId: String?,
    ): ConversationalResponse {
        val matched: ResumeOption? =
            selectedOptionId?.takeIf { it.isNotBlank() }?.let { id ->
                payload.options.firstOrNull { it.id == id }
                    ?: throw ResumeTokenException("selected_option_id '$id' is not among the clarification options")
            }
        val chosen =
            matched?.display
                ?: freeTextAnswer?.takeIf { it.isNotBlank() }
                ?: payload.options.firstOrNull()?.display
                ?: payload.userText
        val spliced = spliceText(payload.userText, payload.clarificationSpan, chosen)
        val context = GolemContext.newBuilder()
        payload.locale.takeIf { it.isNotBlank() }?.let { context.locale = it }
        matched?.takeIf { it.resolvedId.isNotBlank() }?.let { opt ->
            context.handoffBuilder.addEntities(
                EntityBinding
                    .newBuilder()
                    .setEntityType(opt.entityTypeRef)
                    .setEntityId(opt.resolvedId)
                    .setDisplayLabel(opt.display)
                    .setSource("clarification"),
            )
        }
        val request =
            GolemRequest
                .newBuilder()
                .setId(payload.turnId)
                .setGolemId(payload.golemId)
                .setQuestion(spliced)
                .setContext(context)
                .build()
        return answer(request, caller)
    }

    /** Text-splice fallback (`_splice_entity_choice`): replace the clarified span with [chosen]. */
    private fun spliceText(
        original: String,
        span: org.tatrman.kantheon.golem.resume.ClarificationSpan?,
        chosen: String,
    ): String =
        when {
            span != null && span.charStart in 0..span.charEnd && span.charEnd <= original.length ->
                original.substring(0, span.charStart) + chosen + original.substring(span.charEnd)
            span != null && span.coveredText.isNotBlank() && original.contains(span.coveredText) ->
                original.replaceFirst(span.coveredText, chosen)
            else -> chosen
        }

    private fun paramFillResponse(
        state: GolemTurnState,
        caller: AdmittedCaller,
        paramFill: org.tatrman.kantheon.golem.execution.ParamFillNeed,
        responseId: String,
        finalisedAt: String,
    ): ConversationalResponse {
        val plan =
            state.plan ?: org.tatrman.kantheon.golem.v1.MiniPlan
                .getDefaultInstance()
        val token =
            resumeCodec.encode(
                ResumePayload(
                    threadId = "", // thread is BFF-carried; the turn id is the canonical anchor
                    turnId = state.request.id,
                    kind = ParamFill.KIND,
                    userText = state.request.question,
                    pickedPlanJson = ParamFill.planToJson(plan),
                    options = listOf(ResumeOption(id = paramFill.paramName, display = paramFill.label)),
                    // B1 caller binding + M5 rehydration — see ResumePayload.
                    userId = caller.userId,
                    tenantId = caller.tenantId,
                    golemId = state.request.golemId,
                    locale = state.request.context.locale,
                ),
            )
        val envelope =
            ParamFill.clarificationEnvelope(
                turnId = state.request.id,
                agentId = state.request.golemId,
                paramName = paramFill.paramName,
                label = paramFill.label,
                resumeToken = token,
            )
        return ConversationalResponse
            .newBuilder()
            .setId(responseId)
            .setRequestId(state.request.id)
            .setGolemId(state.request.golemId)
            .setFinalisedAt(finalisedAt)
            .addEnvelopes(envelope)
            .also { b -> state.plan?.let { b.plan = it } }
            .setStatus(Status.STATUS_CLARIFICATION)
            .build()
    }

    private fun assembleResponse(
        state: GolemTurnState,
        responseId: String,
        finalisedAt: String,
    ): ConversationalResponse {
        val b =
            ConversationalResponse
                .newBuilder()
                .setId(responseId)
                .setRequestId(state.request.id)
                .setGolemId(state.request.golemId)
                .setFinalisedAt(finalisedAt)
        state.plan?.let { b.plan = it }
        when {
            state.execution != null -> {
                b.addAllEnvelopes(state.execution.envelopes)
                b.addAllStepRecords(state.execution.stepRecords)
                b.resourceUsage = state.execution.resourceUsage
                // current_view = what the user is now looking at (agent-owned; the BFF
                // snapshots it into the TurnPointer). applied_context = the entity
                // bindings carried into this turn from the handoff.
                state.execution.currentView?.let { b.currentView = it }
                b.addAllAppliedContext(appliedContext(state.request))
            }
            state.clarification != null -> b.addEnvelopes(state.clarification)
        }
        b.status =
            when (state.outcome) {
                TurnOutcome.CLARIFY -> Status.STATUS_CLARIFICATION
                TurnOutcome.FAILED -> Status.STATUS_FAILED
                else -> state.execution?.status ?: Status.STATUS_FAILED
            }
        return b.build()
    }

    /** The entity bindings applied to this turn — those carried in via the handoff. */
    private fun appliedContext(request: GolemRequest): List<EntityBinding> =
        if (request.context.hasHandoff()) request.context.handoff.entitiesList else emptyList()

    private fun persistTurn(
        request: GolemRequest,
        caller: AdmittedCaller,
        response: ConversationalResponse,
        createdAt: Instant,
    ) {
        turns.insert(
            GolemTurnRecord(
                id = toUuid(response.id),
                requestId = toUuid(request.id),
                golemId = request.golemId,
                userId = caller.userId,
                tenantId = caller.tenantId,
                question = request.question,
                resolvedIntentJson = ProtoJson.print(request.resolvedIntent),
                planJson = if (response.hasPlan()) ProtoJson.print(response.plan) else "{}",
                envelopesJson = ProtoJson.printList(response.envelopesList),
                // Persist the full current_view (proto3-JSON carries `bubbleId`, which
                // [TurnsRepository.findByBubbleId] keys on for AMEND/DRILL lookup).
                currentViewJson = if (response.hasCurrentView()) ProtoJson.print(response.currentView) else null,
                stepRecordsJson = ProtoJson.printList(response.stepRecordsList),
                resourceUsageJson = if (response.hasResourceUsage()) ProtoJson.print(response.resourceUsage) else "{}",
                status = response.status.toTurnStatus(),
                createdAt = createdAt,
                finalisedAt = createdAt,
            ),
        )
    }

    private fun toUuid(s: String): UUID =
        runCatching {
            UUID.fromString(s)
        }.getOrElse { UUID.nameUUIDFromBytes(s.toByteArray()) }

    private fun Status.toTurnStatus(): GolemTurnStatus =
        when (this) {
            Status.STATUS_CLARIFICATION -> GolemTurnStatus.CLARIFICATION
            Status.STATUS_FAILED -> GolemTurnStatus.FAILED
            else -> GolemTurnStatus.DONE
        }
}
