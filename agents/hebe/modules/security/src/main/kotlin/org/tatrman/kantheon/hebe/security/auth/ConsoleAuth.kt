package org.tatrman.kantheon.hebe.security.auth

import org.tatrman.kantheon.hebe.config.ConsoleAuth as ConsoleAuthAxis

/**
 * Console authentication (P2 Stage 2.3 T3; architecture §6). This is the
 * **independent** second auth concern: how the *human* logs into Hebe's own
 * admin console — orthogonal to [OboTokenService] (the outbound platform
 * identity). Both axes are real, live code paths (no compile-out):
 *
 *  - `password` — the existing admin password + keychain (`local`/`personal`).
 *  - `oidc` — a Keycloak-issued bearer the console verifies ([OidcSessionVerifier]).
 *
 * The selection is driven by the `console_auth` axis, never the profile name.
 */
enum class ConsoleAuthMode {
    PASSWORD,
    OIDC,
    ;

    companion object {
        fun from(axis: ConsoleAuthAxis): ConsoleAuthMode =
            when (axis) {
                ConsoleAuthAxis.PASSWORD -> PASSWORD
                ConsoleAuthAxis.OIDC -> OIDC
            }
    }
}

/** The outcome of verifying a console credential. */
sealed interface ConsoleAuthResult {
    data class Authenticated(val subject: String) : ConsoleAuthResult

    data class Rejected(val reason: String) : ConsoleAuthResult
}

/**
 * Verifies an OIDC console session. The full ktor-server-auth browser auth-code
 * flow wires this into the web console; the verifier itself is the testable core:
 * given a Keycloak access token, confirm issuer + audience + expiry and extract
 * the subject. Signature/JWKS verification is delegated to [validateSignature]
 * (a seam the console binds to Keycloak's JWKS; the unit test injects a stub).
 *
 * [validateSignature] is **required** (no default): an accept-all default could
 * silently reach production via omission, yielding a forgeable console session
 * with issuer/aud/exp checks but no signature verification. Callers must pass an
 * explicit validator (the real JWKS check in production; a stub in tests).
 */
class OidcSessionVerifier(
    private val expectedIssuer: String,
    private val expectedAudience: String?,
    private val validateSignature: (String) -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** Verify a bearer's claims. [claims] is the decoded payload (iss/aud/exp/sub/preferred_username). */
    fun verify(
        rawToken: String,
        claims: Map<String, Any?>,
    ): ConsoleAuthResult {
        if (!validateSignature(rawToken)) return ConsoleAuthResult.Rejected("signature invalid")
        if (claims["iss"] != expectedIssuer) return ConsoleAuthResult.Rejected("issuer mismatch")
        if (expectedAudience != null) {
            val aud = claims["aud"]
            val ok = aud == expectedAudience || (aud as? List<*>)?.contains(expectedAudience) == true
            if (!ok) return ConsoleAuthResult.Rejected("audience mismatch")
        }
        val exp = (claims["exp"] as? Number)?.toLong()
        if (exp != null && exp <= now()) return ConsoleAuthResult.Rejected("token expired")
        val subject = (claims["preferred_username"] ?: claims["sub"]) as? String
            ?: return ConsoleAuthResult.Rejected("no subject")
        return ConsoleAuthResult.Authenticated(subject)
    }
}
