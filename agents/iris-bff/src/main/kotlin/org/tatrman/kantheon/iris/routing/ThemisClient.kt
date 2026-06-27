package org.tatrman.kantheon.iris.routing

import org.tatrman.kantheon.themis.v1.Themis.ResolveRequest
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse

/**
 * Routing edge to Themis (contracts §2.2, Phase 3 Stage 3.1). The BFF resolves
 * every turn through `understand()` before dispatch; the result's outcome oneof
 * (`Resolution` / `AwaitingClarification` / `RefusalWithGaps`) drives the
 * dispatch branch in [org.tatrman.kantheon.iris.api.ChatDispatcher].
 *
 * Identity discipline (kantheon-security §2/D3): the caller's **OBO bearer** is
 * forwarded verbatim; Themis reads `realm_access.roles` from it. No service
 * identity, no roles field on the request.
 */
interface ThemisClient {
    /**
     * Resolve a turn. Throws [ThemisAuthException] on a 401/403 (expired/invalid
     * OBO bearer — fail closed, kantheon-security §2) and [ThemisUnavailableException]
     * on transport failure / other non-2xx.
     */
    suspend fun understand(
        request: ResolveRequest,
        bearer: String,
    ): ResolveResponse
}

/** Themis unreachable / non-2xx (non-auth) — the BFF degrades to a THEMIS_UNAVAILABLE error envelope. */
class ThemisUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Themis rejected the OBO bearer (401/403) — the caller must re-authenticate;
 *  the BFF surfaces a non-recoverable AUTH_EXPIRED error rather than inviting a retry. */
class ThemisAuthException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
