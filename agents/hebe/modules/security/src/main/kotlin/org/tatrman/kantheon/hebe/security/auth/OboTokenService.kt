package org.tatrman.kantheon.hebe.security.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * Mints + caches the **on-behalf-of** bearer Hebe uses for outbound iris-bff
 * calls (P2 Stage 2.3 T4; contracts §3.2). The *same* bound-user model serves
 * all three platform-reaching profiles (`personal`/`server`/`k8s`); only the
 * **grant path** differs (the [GrantStrategy]):
 *
 *  - device-code + cached refresh-token exchange — `personal`/`server`
 *  - client-credentials → token-exchange for the bound user — `k8s`
 *
 * iris-bff sees a normal *user* bearer (no service-account path); the constellation
 * applies PD-8 authorization off that token, unchanged. Phase 4 Stage 4.1 consumes
 * [currentBearer]. The acting identity (the bound user) is recorded on receipts by
 * the caller.
 */
class OboTokenService(
    private val strategy: GrantStrategy,
    /** Re-mint when fewer than this many seconds of validity remain. */
    private val refreshSkewSeconds: Long = 30,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()
    private var cached: CachedToken? = null

    private data class CachedToken(
        val accessToken: String,
        val expiresAtEpochSec: Long,
    )

    /** The bound user this service acts for. */
    val boundUser: String get() = strategy.boundUser

    /**
     * Returns a valid OBO access token, minting or refreshing through the grant
     * strategy only when the cache is empty or within [refreshSkewSeconds] of
     * expiry. Concurrent callers share one mint (the mutex).
     */
    suspend fun currentBearer(): String =
        mutex.withLock {
            val c = cached
            if (c != null && c.expiresAtEpochSec - now() > refreshSkewSeconds) {
                return c.accessToken
            }
            val minted = strategy.mint()
            cached = CachedToken(minted.accessToken, now() + minted.expiresInSeconds)
            minted.accessToken
        }
}

/** A minted token + its lifetime, from a [GrantStrategy]. */
data class MintedToken(
    val accessToken: String,
    val expiresInSeconds: Long,
)

/** Pluggable Keycloak grant path. */
interface GrantStrategy {
    val boundUser: String

    suspend fun mint(): MintedToken
}

/**
 * `personal`/`server`: the bound user logged in once via device-code; we hold the
 * resulting **refresh token** and exchange it for a fresh access token. (The
 * interactive device-code step itself is out of the hot path — it seeds the
 * refresh token; this strategy keeps it fresh.)
 *
 * Keycloak **rotates** the refresh token on each exchange (default), invalidating
 * the one just used. We therefore hold the current refresh token in a mutable
 * [AtomicReference] seeded with the initial seed token, and advance it to the
 * rotated `refresh_token` from each response (keeping the previous value if a
 * response omits one) — so later mints don't reuse a now-invalid token.
 * [OboTokenService] serialises mints under its mutex, so the holder need only be
 * mutable, not contended.
 */
class RefreshTokenGrant(
    override val boundUser: String,
    private val tokenUrl: String,
    private val clientId: String,
    refreshToken: String,
    private val http: HttpClient,
    private val json: Json = LENIENT_JSON,
) : GrantStrategy {
    /** Current refresh token; advanced to the rotated value after each exchange. */
    private val currentRefreshToken = AtomicReference(refreshToken)

    override suspend fun mint(): MintedToken {
        val resp =
            http.submitForm(
                url = tokenUrl,
                formParameters =
                    Parameters.build {
                        append("grant_type", "refresh_token")
                        append("client_id", clientId)
                        append("refresh_token", currentRefreshToken.get())
                    },
            )
        require(resp.status.isSuccess()) { "Keycloak refresh-token grant failed: HTTP ${resp.status.value}" }
        val body = json.decodeFromString<TokenResponse>(resp.bodyAsText())
        // Adopt the rotated refresh token; keep the previous one if none was returned.
        body.refreshToken?.let { currentRefreshToken.set(it) }
        return MintedToken(body.accessToken, body.expiresIn)
    }
}

/**
 * `k8s`: the pod authenticates with client-credentials, then exchanges that for
 * the bound user's token (RFC 8693 token-exchange) — yielding a *user* OBO bearer
 * iris-bff accepts as a normal user.
 */
class ClientCredentialsExchangeGrant(
    override val boundUser: String,
    private val tokenUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val http: HttpClient,
    private val json: Json = LENIENT_JSON,
) : GrantStrategy {
    override suspend fun mint(): MintedToken {
        // 1) client-credentials → a service access token
        val ccResp =
            http.submitForm(
                url = tokenUrl,
                formParameters =
                    Parameters.build {
                        append("grant_type", "client_credentials")
                        append("client_id", clientId)
                        append("client_secret", clientSecret)
                    },
            )
        require(ccResp.status.isSuccess()) { "Keycloak client-credentials grant failed: HTTP ${ccResp.status.value}" }
        val serviceToken = json.decodeFromString<TokenResponse>(ccResp.bodyAsText()).accessToken

        // 2) token-exchange → the bound user's OBO token
        val exResp =
            http.submitForm(
                url = tokenUrl,
                formParameters =
                    Parameters.build {
                        append("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        append("client_id", clientId)
                        append("client_secret", clientSecret)
                        append("subject_token", serviceToken)
                        append("requested_subject", boundUser)
                    },
            )
        require(exResp.status.isSuccess()) { "Keycloak token-exchange failed: HTTP ${exResp.status.value}" }
        val body = json.decodeFromString<TokenResponse>(exResp.bodyAsText())
        return MintedToken(body.accessToken, body.expiresIn)
    }
}

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 300,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

internal val LENIENT_JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
