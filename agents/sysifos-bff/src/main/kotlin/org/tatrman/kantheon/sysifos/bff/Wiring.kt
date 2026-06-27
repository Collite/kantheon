package org.tatrman.kantheon.sysifos.bff

import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.bffbase.auth.BearerAuthenticator
import org.tatrman.kantheon.bffbase.auth.HttpJwksProvider
import org.tatrman.kantheon.bffbase.auth.RsaJwksSignatureVerifier
import org.tatrman.kantheon.bffbase.health.Readiness
import org.tatrman.kantheon.sysifos.bff.dictionaries.DictionaryService
import org.tatrman.kantheon.sysifos.bff.midas.MidasCoreClient
import org.tatrman.kantheon.sysifos.bff.session.DraftScratch
import org.tatrman.kantheon.sysifos.bff.session.InMemorySessionStore
import org.tatrman.kantheon.sysifos.bff.session.SessionStore
import org.tatrman.kantheon.sysifos.bff.stream.SessionStreamBus
import org.tatrman.kantheon.sysifos.bff.write.DraftStateMachine
import org.tatrman.kantheon.sysifos.bff.write.defaultCommitters
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.sysifos.bff.Wiring")

/** The wired-up components for a running sysifos-bff (keeps Application.kt thin). */
class SysifosDeps(
    val auth: BearerAuthenticator,
    val sessions: SessionStore,
    val dictionaries: DictionaryService,
    val readiness: Readiness,
    val heartbeatMs: Long,
    val midas: MidasCoreClient,
    val loader: MidasCoreClient,
    val bus: SessionStreamBus,
    val draftScratch: DraftScratch,
    val stateMachine: DraftStateMachine,
    /** When non-null, module() runs it on a ticker to refresh `/ready`'s Midas reachability. */
    val reachabilityPoll: (suspend () -> Unit)? = null,
    val onStop: () -> Unit = {},
)

/**
 * Build the production deps from config: bearer auth (decode or JWKS-verified),
 * the Midas-core client, in-memory session store, the dictionary cache, and a
 * readiness gate of `JWKS configured && Midas-core reachable`. Reachability is a
 * cached flag refreshed by a poller (started in module()), so `/ready` never
 * blocks on a downstream call.
 */
fun buildDeps(config: Config): SysifosDeps {
    val authCfg = config.getConfig("sysifos-bff.auth")
    val (auth, jwksReady) = buildAuth(authCfg)

    val midas = MidasCoreClient(config.getString("sysifos-bff.midas.base-url"))
    val loader = MidasCoreClient(config.getString("sysifos-bff.loader.base-url"))
    val midasUp = AtomicBoolean(false)
    val heartbeatMs = config.getLong("sysifos-bff.stream.heartbeat-s") * 1000

    val draftScratch = DraftScratch()
    val readiness = Readiness { jwksReady && midasUp.get() }
    return SysifosDeps(
        auth = auth,
        sessions = InMemorySessionStore(),
        dictionaries = DictionaryService(),
        readiness = readiness,
        heartbeatMs = heartbeatMs,
        midas = midas,
        loader = loader,
        bus = SessionStreamBus(),
        draftScratch = draftScratch,
        stateMachine = DraftStateMachine(defaultCommitters(midas, loader), draftScratch),
        reachabilityPoll = { midasUp.set(midas.reachable()) },
        onStop = {
            midas.close()
            loader.close()
        },
    )
}

/**
 * Bearer authenticator from the `sysifos-bff.auth` config block. Decode mode
 * (signature off — local/dev) returns claims after an `exp` check; signature
 * mode wires an [HttpJwksProvider] + [RsaJwksSignatureVerifier] over the JWKS URI
 * (explicit `jwks-uri`, else derived from `keycloak-issuer`). Returns the auth +
 * whether the JWKS leg is considered ready for `/ready`.
 */
fun buildAuth(authCfg: Config): Pair<BearerAuthenticator, Boolean> {
    val tenantClaim = if (authCfg.hasPath("tenant-claim")) authCfg.getString("tenant-claim") else "tenant"
    val defaultTenant = if (authCfg.hasPath("default-tenant")) authCfg.getString("default-tenant") else "default"
    val verify = authCfg.hasPath("verify-signature") && authCfg.getBoolean("verify-signature")

    if (!verify) {
        log.info("sysifos-bff auth: decode mode (signature verification off)")
        return BearerAuthenticator(tenantClaim = tenantClaim, defaultTenant = defaultTenant) to true
    }

    val jwksUri =
        when {
            authCfg.hasPath("jwks-uri") -> authCfg.getString("jwks-uri")
            authCfg.hasPath("keycloak-issuer") ->
                "${authCfg.getString("keycloak-issuer").trimEnd('/')}/protocol/openid-connect/certs"
            else -> error("sysifos-bff auth: verify-signature = true requires jwks-uri or keycloak-issuer")
        }
    val issuer = if (authCfg.hasPath("keycloak-issuer")) authCfg.getString("keycloak-issuer") else null
    val audience = if (authCfg.hasPath("audience")) authCfg.getString("audience") else null
    log.info("sysifos-bff auth: JWKS signature mode (jwksUri={}, issuer={}, audience={})", jwksUri, issuer, audience)

    val verifier = RsaJwksSignatureVerifier(HttpJwksProvider(jwksUri), issuer = issuer, audience = audience)
    return BearerAuthenticator(
        tenantClaim = tenantClaim,
        defaultTenant = defaultTenant,
        verifySignature = true,
        signatureVerifier = verifier,
    ) to true
}
