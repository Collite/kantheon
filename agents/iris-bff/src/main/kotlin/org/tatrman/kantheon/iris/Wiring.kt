package org.tatrman.kantheon.iris

import com.typesafe.config.Config
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.iris.action.EscalationHandler
import org.tatrman.kantheon.iris.action.ReaskHandler
import org.tatrman.kantheon.iris.action.TypedActionDispatcher
import org.tatrman.kantheon.iris.artifact.ArtifactService
import org.tatrman.kantheon.iris.artifact.RoutingArtifactExecutor
import org.tatrman.kantheon.iris.api.BearerAuthenticator
import org.tatrman.kantheon.iris.api.ChatDispatcher
import org.tatrman.kantheon.iris.api.ErrorBody
import org.tatrman.kantheon.iris.api.HttpJwksProvider
import org.tatrman.kantheon.iris.api.JwtSignatureVerifier
import org.tatrman.kantheon.iris.api.Readiness
import org.tatrman.kantheon.iris.api.RoutingMetrics
import org.tatrman.kantheon.iris.api.RsaJwksSignatureVerifier
import org.tatrman.kantheon.iris.api.StaticChipSource
import org.tatrman.kantheon.iris.audit.AuditStore
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2AgentClient
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2HttpClient
import org.tatrman.kantheon.iris.domain.ArtifactStore
import org.tatrman.kantheon.iris.domain.FeedbackStore
import org.tatrman.kantheon.iris.domain.InMemoryArtifactStore
import org.tatrman.kantheon.iris.domain.InMemoryFeedbackStore
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.inbox.FakePythiaClient
import org.tatrman.kantheon.iris.inbox.InboxService
import org.tatrman.kantheon.iris.inbox.LifecycleHub
import org.tatrman.kantheon.iris.inbox.PollingLifecycleDriver
import org.tatrman.kantheon.iris.inbox.PythiaClient
import org.tatrman.kantheon.iris.infra.ExposedArtifactStore
import org.tatrman.kantheon.iris.infra.ExposedAuditStore
import org.tatrman.kantheon.iris.infra.ExposedFeedbackStore
import org.tatrman.kantheon.iris.infra.ExposedSessionStore
import org.tatrman.kantheon.iris.infra.IrisDatabase
import org.tatrman.kantheon.iris.routing.AgentLabels
import org.tatrman.kantheon.iris.routing.CapabilitiesAgentLabels
import org.tatrman.kantheon.iris.routing.HttpThemisClient
import org.tatrman.kantheon.iris.routing.RoutingEnvelopes
import org.tatrman.kantheon.iris.routing.ThemisClient
import org.tatrman.kantheon.iris.stream.IrisStreamMux

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.iris.Wiring")

/** The wired-up components for a running iris-bff (declarative; keeps Application.kt thin). */
class IrisComponents(
    val store: SessionStore,
    val auth: BearerAuthenticator,
    val dispatcher: ChatDispatcher,
    val typedActions: TypedActionDispatcher,
    val reask: ReaskHandler,
    val escalation: EscalationHandler,
    val artifacts: ArtifactStore,
    val artifactService: ArtifactService,
    val inboxService: InboxService,
    val lifecycleHub: LifecycleHub,
    val lifecyclePoller: PollingLifecycleDriver,
    val feedback: FeedbackStore,
    val audit: AuditStore,
    val signer: Ed25519Signer,
    val capabilities: CapabilitiesReadClient,
    val metrics: RoutingMetrics,
    val staticChips: StaticChipSource,
    val golemClient: GolemV2Client,
    val readiness: Readiness,
    val heartbeatMs: Long,
    val meterScrape: () -> String,
    val onStop: () -> Unit,
)

/**
 * Select the session store from config: Postgres (Exposed + Flyway) when
 * `iris.db.enabled`, else the in-memory store (local boot / tests). The DB path
 * runs migrations synchronously at boot and **fails fast** if they don't apply —
 * the server never reaches a serving state with an unmigrated schema, so
 * `/ready` returning true implies migrations completed.
 */
fun buildComponents(config: Config): IrisComponents {
    val auth = buildAuth(config.getConfig("iris.auth"))

    // Audit signing key: loaded from the configured Secret ref (Stage 1.4); falls
    // back to an ephemeral keypair (with a warning) when none is set (dev/local).
    val auditCfg = config.getConfig("iris.audit")
    val signer =
        Ed25519Signer.fromKeyRef(
            if (auditCfg.hasPath("signing-key-ref")) auditCfg.getString("signing-key-ref") else null,
        )

    // Transitional new-golem /v2 dispatch (deleted at Golem cutover).
    val client = GolemV2HttpClient(config.getString("iris.dispatch.golem-v2.base-url"))
    val mux = IrisStreamMux()
    val heartbeatMs = config.getLong("iris.stream.heartbeat-s") * 1000

    // Phase 3 routing edge: every turn resolves through Themis, then dispatches to
    // the chosen agent's client. golem-v2 is the only registered client at Phase 3.
    val themisCfg = config.getConfig("iris.themis")
    val themis: ThemisClient =
        HttpThemisClient(themisCfg.getString("base-url"), timeoutMs = themisCfg.getLong("timeout-ms"))
    val capabilities = CapabilitiesReadClient(config.getString("iris.capabilities.base-url"))
    val labels: AgentLabels = CapabilitiesAgentLabels(capabilities)
    val envelopes = RoutingEnvelopes(labels)

    // Prometheus metrics (Stage 3.3) — domain meters + scraped at GET /metrics.
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val metrics = RoutingMetrics(meterRegistry)

    fun chatDispatcher(
        store: SessionStore,
        audit: AuditStore,
    ): ChatDispatcher {
        val agents = AgentDispatcher(mapOf("golem-v2" to GolemV2AgentClient(store, client, mux)))
        return ChatDispatcher(store, themis, agents, audit, envelopes, metrics = metrics)
    }

    fun typedActions(
        store: SessionStore,
        audit: AuditStore,
    ): TypedActionDispatcher = TypedActionDispatcher(store, client, audit, mux, metrics)

    fun reaskHandler(
        feedback: FeedbackStore,
        audit: AuditStore,
    ): ReaskHandler = ReaskHandler(feedback, audit)

    // Artifact refresh re-executes through the producing agent (golem-v2 at Phase 4),
    // normalised to v1 via the mux; Pythia-kind replay lands in the Pythia arc.
    val artifactExecutor = RoutingArtifactExecutor(client, mux)

    fun artifactService(
        artifacts: ArtifactStore,
        audit: AuditStore,
    ): ArtifactService = ArtifactService(artifacts, artifactExecutor, audit, metrics)

    // Investigation inbox (PD-2). A configured `iris.pythia.url` (Pythia arc Stage 5.2)
    // wires the live HTTP client (submit + per-user list over Pythia's REST surface);
    // blank → FakePythiaClient (local/tests). The polling driver is the lifecycle
    // fallback producer until NATS is connected.
    val pythiaUrl = if (config.hasPath("iris.pythia.url")) config.getString("iris.pythia.url") else ""
    val pythia: PythiaClient =
        if (pythiaUrl.isNotBlank()) {
            org.tatrman.kantheon.iris.inbox
                .LivePythiaClient(pythiaUrl)
        } else {
            FakePythiaClient()
        }
    val lifecycleHub = LifecycleHub()
    val pollFallbackMs =
        (if (config.hasPath("iris.inbox.poll-fallback-s")) config.getLong("iris.inbox.poll-fallback-s") else 30L) * 1000
    val lifecyclePoller = PollingLifecycleDriver(pythia, lifecycleHub, pollFallbackMs)
    // 0 ⇒ polling fallback is the active lifecycle producer (no NATS at Phase 4).
    meterRegistry.gauge("iris_lifecycle_nats_connected", lifecycleHub) { if (it.natsConnected) 1.0 else 0.0 }

    fun inboxService(store: SessionStore): InboxService = InboxService(pythia, store)

    val closeRouting = {
        runCatching { (themis as? AutoCloseable)?.close() }
        runCatching { capabilities.close() }
    }

    if (!config.getBoolean("iris.db.enabled")) {
        log.info("iris-bff using in-memory session store (iris.db.enabled = false)")
        val store = InMemorySessionStore()
        val artifacts = InMemoryArtifactStore()
        // Single shared in-memory audit + feedback so the verify/feedback surfaces
        // see the same rows the write paths produce (the Exposed branch shares one DB).
        val audit = InMemoryAuditStore(signer)
        val feedback: FeedbackStore = InMemoryFeedbackStore()
        return IrisComponents(
            store = store,
            auth = auth,
            dispatcher = chatDispatcher(store, audit),
            typedActions = typedActions(store, audit),
            reask = reaskHandler(feedback, audit),
            escalation = EscalationHandler(audit, metrics = metrics),
            artifacts = artifacts,
            artifactService = artifactService(artifacts, audit),
            inboxService = inboxService(store),
            lifecycleHub = lifecycleHub,
            lifecyclePoller = lifecyclePoller,
            feedback = feedback,
            audit = audit,
            signer = signer,
            capabilities = capabilities,
            metrics = metrics,
            staticChips = StaticChipSource(),
            golemClient = client,
            readiness = Readiness { true },
            heartbeatMs = heartbeatMs,
            meterScrape = { meterRegistry.scrape() },
            onStop = {
                client.close()
                closeRouting()
            },
        )
    }

    val database = IrisDatabase(config)
    val migration = database.migrateAndConnect()
    log.info("iris-bff schema ready: version={} applied={}", migration.version, migration.applied)
    val store = ExposedSessionStore(database.connection)
    val audit = ExposedAuditStore(database.connection, signer)
    val feedback: FeedbackStore = ExposedFeedbackStore(database.connection)
    val artifacts: ArtifactStore = ExposedArtifactStore(database.connection)
    return IrisComponents(
        store = store,
        auth = auth,
        dispatcher = chatDispatcher(store, audit),
        typedActions = typedActions(store, audit),
        reask = reaskHandler(feedback, audit),
        escalation = EscalationHandler(audit, metrics = metrics),
        artifacts = artifacts,
        artifactService = artifactService(artifacts, audit),
        inboxService = inboxService(store),
        lifecycleHub = lifecycleHub,
        lifecyclePoller = lifecyclePoller,
        feedback = feedback,
        audit = audit,
        signer = signer,
        capabilities = capabilities,
        metrics = metrics,
        staticChips = StaticChipSource(),
        golemClient = client,
        readiness = Readiness { true },
        heartbeatMs = heartbeatMs,
        meterScrape = { meterRegistry.scrape() },
        onStop = {
            client.close()
            closeRouting()
            database.close()
        },
    )
}

/**
 * Build the bearer authenticator. With `verify-signature = true` a JWKS-backed
 * RS256 verifier is wired from `jwks-uri` (explicit) or derived from
 * `keycloak-issuer` (`…/protocol/openid-connect/certs`); `iss`/`aud` are enforced
 * when configured. If verification is on but no JWKS source is configured the
 * verifier is left null and [BearerAuthenticator] fails closed (logs + 401s).
 */
private fun buildAuth(authCfg: Config): BearerAuthenticator {
    val verify = authCfg.getBoolean("verify-signature")
    if (!verify) {
        // Role-based gates (PD-7 discover filtering, PD-8 audit admin) read roles
        // from the *unverified* JWT payload in decode mode — a forged bearer can
        // assert any role. Acceptable for local/dev only; loud in production logs.
        log.warn(
            "iris auth: verify-signature = false — bearer claims (incl. roles for discover/audit " +
                "gates) are NOT cryptographically verified. Set iris.auth.verify-signature = true in production.",
        )
    }
    val issuer = if (authCfg.hasPath("keycloak-issuer")) authCfg.getString("keycloak-issuer") else null
    val audience = if (authCfg.hasPath("audience")) authCfg.getString("audience") else null

    val verifier: JwtSignatureVerifier? =
        if (!verify) {
            null
        } else {
            val jwksUri =
                when {
                    authCfg.hasPath("jwks-uri") -> authCfg.getString("jwks-uri")
                    issuer != null -> "${issuer.trimEnd('/')}/protocol/openid-connect/certs"
                    else -> null
                }
            if (jwksUri == null) {
                log.error(
                    "iris auth: verify-signature = true but neither iris.auth.jwks-uri nor " +
                        "iris.auth.keycloak-issuer is set — all bearers will be rejected",
                )
                null
            } else {
                log.info("iris auth: JWKS signature verification enabled (uri={})", jwksUri)
                RsaJwksSignatureVerifier(HttpJwksProvider(jwksUri), issuer = issuer, audience = audience)
            }
        }

    return BearerAuthenticator(
        tenantClaim = authCfg.getString("tenant-claim"),
        defaultTenant = authCfg.getString("default-tenant"),
        verifySignature = verify,
        signatureVerifier = verifier,
    )
}

/** Uncaught-exception → 500 mapping with a typed body (avoids leaking stack traces). */
fun Application.installErrorPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on {}", call.request.local.uri, cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal_error", "Unexpected server error"))
        }
    }
}
