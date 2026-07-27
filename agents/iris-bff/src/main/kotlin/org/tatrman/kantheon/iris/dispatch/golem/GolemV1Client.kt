package org.tatrman.kantheon.iris.dispatch.golem

import kotlinx.coroutines.flow.Flow
import org.tatrman.kantheon.golem.v1.GolemRequest

/**
 * Dispatch client for the **native kantheon Golem** REST surface (golem contracts §2–§3).
 * The pod serves exactly four routes — `POST /v1/answer` (SSE), `/v1/answer/sync`,
 * `/v1/resume`, `/v1/refresh` — and has no session concept: a turn is self-contained, so
 * unlike the transitional /v2 client there is no thread to create or bind.
 *
 * Identity discipline (kantheon-security): the caller's **OBO bearer** is forwarded as
 * `Authorization: Bearer <token>`, never service identity. Golem's `ShemAdmission`
 * re-checks the Shem's `visibility_roles` against that bearer's realm roles, so a caller
 * Themis was willing to route can still be refused here — by design.
 */
interface GolemV1Client {
    /** Run a turn. Cold flow of golem SSE events; terminal is [GolemV1Event.Turn] or Error. */
    fun answer(
        request: GolemRequest,
        correlationId: String,
        bearer: String,
    ): Flow<GolemV1Event>

    /**
     * Resume a pending clarification. `/v1/resume` is **synchronous** (it returns a
     * `ConversationalResponse`, not a stream), so this emits a single terminal event and
     * completes — keeping the mux path uniform with [answer].
     */
    fun resume(
        request: GolemResume,
        correlationId: String,
        bearer: String,
    ): Flow<GolemV1Event>
}

/** `POST /v1/resume` body. The token is minted by golem and bound to its issuing caller. */
data class GolemResume(
    val resumeToken: String,
    val selectedOptionId: String? = null,
    val freeTextAnswer: String? = null,
)
