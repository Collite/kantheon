package org.tatrman.kantheon.iris.dispatch.golemv2

import kotlinx.coroutines.flow.Flow

/**
 * Transitional dispatch client for the new-golem /v2 backend (contracts §5).
 * Quarantined behind this interface so the rest of iris-bff never touches v2
 * shapes; deleted at the Golem-rewrite cutover. Identity discipline
 * (kantheon-security): the caller's **OBO bearer** is forwarded as
 * `Authorization: Bearer <token>` (never service identity); `X-User-ID` and
 * `X-Correlation-Id` ride alongside as BFF-injected context.
 */
interface GolemV2Client {
    /** Create (or rehydrate) the v2 thread. thread_id is client-supplied (OQ-04.A). */
    suspend fun createSession(
        threadId: String,
        userId: String,
        correlationId: String,
        bearer: String,
        locale: String = "cs",
    ): V2SessionStartResponse

    /** Stream a turn. Cold flow of v2 SSE events; terminal is Envelope or Error. */
    fun chatStream(
        req: V2ChatRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): Flow<V2StreamEvent>

    /**
     * Refetch a typed action that can't be served from the cached rows (a filter
     * widening beyond the cached page, or a drilldown to another pattern). Wraps
     * the producing agent's typed-action surface (transitionally `/v2/action`) and
     * emits the new/replacing Envelope as an SSE flow (contracts §2.4 / §5).
     */
    fun reissueAction(
        req: V2ActionRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): Flow<V2StreamEvent>

    /** Resume a pending clarification. Emits the resolved Envelope (or Error). */
    fun resume(
        req: V2ResumeRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): Flow<V2StreamEvent>

    /** Metadata-service refresh passthrough (`/refresh` slash command). Empty
     *  service → refresh all in dependency order (golem orchestrates). */
    suspend fun refresh(
        req: V2RefreshRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): V2RefreshResponse
}
