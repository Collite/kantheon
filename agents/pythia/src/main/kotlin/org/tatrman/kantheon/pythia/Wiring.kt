package org.tatrman.kantheon.pythia

import com.typesafe.config.Config
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.pythia.api.ArtifactAssembler
import org.tatrman.kantheon.pythia.api.Readiness
import org.tatrman.kantheon.pythia.auth.Admission
import org.tatrman.kantheon.pythia.auth.BearerAdmission
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.events.LoggingNatsPublisher
import org.tatrman.kantheon.pythia.events.NatsPublisher
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.llm.client.LlmGatewayClient
import org.tatrman.llm.client.LlmGatewayEndpoint
import org.tatrman.llm.client.LlmGatewayPromptExecutor
import org.tatrman.kantheon.pythia.evaluate.HypothesisEvaluator
import org.tatrman.kantheon.pythia.executor.CompositeNodeExecutor
import org.tatrman.kantheon.pythia.executor.DagExecutor
import org.tatrman.kantheon.pythia.executor.DataFrameNodeExecutor
import org.tatrman.kantheon.pythia.executor.QueryNodeExecutor
import org.tatrman.kantheon.pythia.clients.QueryQueryClient
import org.tatrman.kantheon.pythia.orchestrator.ExecutionEngine
import org.tatrman.kantheon.pythia.orchestrator.InvestigationOrchestrator
import org.tatrman.kantheon.pythia.orchestrator.TtlSweeper
import org.tatrman.kantheon.pythia.plan.CapabilityChecker
import org.tatrman.kantheon.pythia.plan.PlanComposer
import org.tatrman.kantheon.pythia.plan.PlanValidator
import org.tatrman.kantheon.pythia.plan.Planner
import org.tatrman.kantheon.pythia.plan.RegistryCapabilityChecker
import org.tatrman.kantheon.pythia.resolve.HttpThemisClient
import org.tatrman.kantheon.pythia.resolve.Resolver
import org.tatrman.kantheon.pythia.revise.PlanReviser
import org.tatrman.kantheon.pythia.suspicion.SuspicionClassifier
import org.tatrman.kantheon.pythia.suspicion.SuspicionPolicyHandler
import org.tatrman.kantheon.pythia.synth.RenderNodeExecutor
import org.tatrman.kantheon.pythia.synth.ReasoningNodeExecutor
import org.tatrman.kantheon.pythia.synth.Synthesizer
import org.tatrman.kantheon.pythia.persistence.Checkpointer
import org.tatrman.kantheon.pythia.persistence.EventRepository
import org.tatrman.kantheon.pythia.persistence.ExposedCheckpointRepository
import org.tatrman.kantheon.pythia.persistence.ExposedEventRepository
import org.tatrman.kantheon.pythia.persistence.ExposedHandleRepository
import org.tatrman.kantheon.pythia.persistence.ExposedHypothesisRepository
import org.tatrman.kantheon.pythia.persistence.ExposedInvestigationRepository
import org.tatrman.kantheon.pythia.persistence.ExposedStepRepository
import org.tatrman.kantheon.pythia.persistence.HandleRepository
import org.tatrman.kantheon.pythia.persistence.HypothesisRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryCheckpointRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryHandleRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryHypothesisRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryInvestigationRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryStepRepository
import org.tatrman.kantheon.pythia.persistence.InvestigationRepository
import org.tatrman.kantheon.pythia.persistence.PythiaDatabase
import org.tatrman.kantheon.pythia.persistence.StepRepository
import java.time.Clock

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.pythia.Wiring")

/** Wired-up components for a running pythia pod (keeps Application.kt thin). */
class PythiaComponents(
    val investigations: InvestigationRepository,
    val hypotheses: HypothesisRepository,
    val steps: StepRepository,
    val handles: HandleRepository,
    val events: EventRepository,
    val checkpointer: Checkpointer,
    val emitter: EventEmitter,
    val orchestrator: InvestigationOrchestrator,
    val assembler: ArtifactAssembler,
    val admission: Admission,
    val sweeper: TtlSweeper,
    val readiness: Readiness,
    val meterRegistry: io.micrometer.prometheusmetrics.PrometheusMeterRegistry,
    val onStop: () -> Unit,
)

/** `/ready` gate: DB migrated (fail-fast at boot, so this is true once we get here). */
class PythiaReadiness(
    private val dbReady: Boolean,
) : Readiness {
    override fun isReady(): Boolean = dbReady
}

private class RepoBundle(
    val investigations: InvestigationRepository,
    val hypotheses: HypothesisRepository,
    val steps: StepRepository,
    val handles: HandleRepository,
    val checkpoints: org.tatrman.kantheon.pythia.persistence.CheckpointRepository,
    val events: EventRepository,
    val onStop: () -> Unit,
)

/**
 * Assemble the full component set. `scope` drives investigation coroutines (the
 * Ktor application is the supervised scope). Repositories are Postgres (Exposed +
 * Flyway) when `pythia.db.enabled`, else in-memory (local boot / tests). The DB
 * path migrates synchronously at boot and **fails fast** on a bad migration.
 */
fun buildComponents(
    config: Config,
    scope: CoroutineScope,
    clock: Clock = Clock.systemUTC(),
): PythiaComponents {
    val repos = buildRepos(config)
    val nats: NatsPublisher = LoggingNatsPublisher() // real JetStream wiring is integration-deferred
    val emitter = EventEmitter(repos.events, nats, clock)
    val meterRegistry =
        io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
            io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT,
        )
    val metrics =
        org.tatrman.kantheon.pythia.obs
            .PythiaMetrics(meterRegistry)
    val checkpointer = Checkpointer(repos.checkpoints, repos.investigations, clock, metrics)
    val ttlHours = config.getLong("pythia.awaiting.ttl-hours")
    val intel = buildIntelligence(config, emitter, repos.steps, metrics, meterRegistry)
    // Master-of-Golems (Stage 5.1 T5): register Pythia as a routable INVESTIGATOR +
    // heartbeat (warn-and-continue — a missing registry never blocks boot).
    val registration = PythiaRegistration(config.getString("pythia.capabilities.url"), log = { log.info(it) })
    registration.start()
    val orchestrator =
        InvestigationOrchestrator(
            repos.investigations,
            checkpointer,
            emitter,
            scope,
            ttlHours,
            clock,
            resolver = intel.resolver,
            planner = intel.planner,
            executionEngine = intel.engine,
            hypothesesRepo = repos.hypotheses,
            metrics = metrics,
        )
    return PythiaComponents(
        investigations = repos.investigations,
        hypotheses = repos.hypotheses,
        steps = repos.steps,
        handles = repos.handles,
        events = repos.events,
        checkpointer = checkpointer,
        emitter = emitter,
        orchestrator = orchestrator,
        assembler = ArtifactAssembler(repos.investigations, repos.hypotheses, repos.steps),
        admission = BearerAdmission(),
        sweeper = TtlSweeper(repos.investigations, emitter, clock),
        readiness = PythiaReadiness(dbReady = true),
        meterRegistry = meterRegistry,
        onStop = {
            registration.shutdown()
            intel.close()
            repos.onStop()
        },
    )
}

/** The Themis/LLM/query-backed intelligence, wired when configured; null → stubs. */
private class Intelligence(
    val resolver: Resolver?,
    val planner: Planner?,
    val engine: ExecutionEngine?,
    val close: () -> Unit,
)

private fun buildIntelligence(
    config: Config,
    emitter: EventEmitter,
    steps: StepRepository,
    metrics: org.tatrman.kantheon.pythia.obs.PythiaMetrics,
    meterRegistry: io.micrometer.core.instrument.MeterRegistry,
): Intelligence {
    val closeables = mutableListOf<() -> Unit>()
    val themisUrl = config.getString("pythia.themis.url")
    val resolver =
        if (themisUrl.isNotBlank()) {
            val client = HttpThemisClient(themisUrl)
            closeables += client::close
            Resolver(client)
        } else {
            null
        }

    val gatewayHost = config.getString("pythia.llm-gateway.host")
    if (gatewayHost.isBlank()) {
        return Intelligence(resolver, null, null) { closeables.forEach { runCatching { it() } } }
    }

    val llm =
        LlmGatewayClient(
            LlmGatewayEndpoint(
                host = gatewayHost,
                port = config.getInt("pythia.llm-gateway.port"),
                timeoutMs = config.getLong("pythia.llm-gateway.timeout-ms"),
            ),
        )
    closeables += llm::close
    val promptExecutor = LlmGatewayPromptExecutor(llm)

    val capUrl = config.getString("pythia.capabilities.url")
    var shemReader: org.tatrman.kantheon.pythia.plan.ShemReader? = null
    val checker: CapabilityChecker =
        if (capUrl.isNotBlank()) {
            val capClient = CapabilitiesReadClient(capUrl)
            closeables += capClient::close
            // Master-of-Golems (Stage 5.1): the same registry client backs Shem reads.
            shemReader =
                org.tatrman.kantheon.pythia.plan
                    .ShemReader { capClient.listAgents() }
            RegistryCapabilityChecker(capClient)
        } else {
            CapabilityChecker { true } // no registry → structural validation only
        }
    val planner = Planner(PlanComposer(promptExecutor), PlanValidator(checker), shemReader = shemReader)

    // Phase 4 data plane — Charon (gRPC) + the Polars worker (gRPC) + Metis (gRPC),
    // wired only when configured. Off → SQL-only: DataFrame/Model nodes fail closed,
    // no Seaweed evidence persistence, the IN-list>500 path stays a PERMANENT flag.
    val dataPlane = buildDataPlane(config, closeables, metrics)

    // Execution engine — wired only when the query edge (query-mcp) is also configured.
    val queryUrl = config.getString("pythia.query.url")
    val engine =
        if (queryUrl.isNotBlank()) {
            val composite =
                CompositeNodeExecutor(
                    query =
                        QueryNodeExecutor(
                            QueryQueryClient(queryUrl),
                            inListMaterialiser = dataPlane?.inListMaterialiser,
                        ),
                    render = RenderNodeExecutor(promptExecutor),
                    reasoning = ReasoningNodeExecutor(promptExecutor),
                    dataframe = dataPlane?.let { DataFrameNodeExecutor(it.worker) },
                    model = dataPlane?.model,
                )
            ExecutionEngine(
                DagExecutor(emitter, steps, composite, metrics = metrics),
                HypothesisEvaluator(emitter, executor = promptExecutor, metrics = meterRegistry),
                Synthesizer(promptExecutor, emitter),
                suspicionClassifier = SuspicionClassifier(),
                suspicionPolicy = SuspicionPolicyHandler(emitter),
                reviser = PlanReviser(promptExecutor, PlanValidator(checker), emitter),
                evidenceManager = dataPlane?.evidenceManager,
            )
        } else {
            null
        }
    return Intelligence(resolver, planner, engine) { closeables.forEach { runCatching { it() } } }
}

/** The wired Phase-4 data plane (Charon + worker + Metis + materialisation collaborators). */
private class DataPlane(
    val worker: org.tatrman.kantheon.pythia.dataplane.WorkerClient,
    val inListMaterialiser: org.tatrman.kantheon.pythia.dataplane.InListMaterialiser,
    val evidenceManager: org.tatrman.kantheon.pythia.dataplane.EvidenceManager,
    val model: org.tatrman.kantheon.pythia.executor.ModelNodeExecutor?,
)

private fun buildDataPlane(
    config: Config,
    closeables: MutableList<() -> Unit>,
    metrics: org.tatrman.kantheon.pythia.obs.PythiaMetrics,
): DataPlane? {
    val charonHost = config.getString("pythia.charon.host")
    val workerHost = config.getString("pythia.worker.host")
    if (charonHost.isBlank() || workerHost.isBlank()) return null
    val charon =
        org.tatrman.kantheon.pythia.dataplane.GrpcCharonClient
            .forAddress(charonHost, config.getInt("pythia.charon.port"))
    closeables += charon::close
    val worker =
        org.tatrman.kantheon.pythia.dataplane.GrpcWorkerClient
            .forAddress(workerHost, config.getInt("pythia.worker.port"))
    closeables += worker::close
    val policy =
        org.tatrman.kantheon.pythia.dataplane
            .MaterialisationPolicy()
    val materialiser =
        org.tatrman.kantheon.pythia.dataplane
            .Materialiser(charon, metrics)

    // Metis (ModelNode) — wired only when a host is configured (Stage 4.2).
    val metisHost = config.getString("pythia.metis.host")
    val model =
        if (metisHost.isNotBlank()) {
            val metis =
                org.tatrman.kantheon.pythia.dataplane.GrpcMetisClient
                    .forAddress(metisHost, config.getInt("pythia.metis.port"))
            closeables += metis::close
            org.tatrman.kantheon.pythia.executor
                .ModelNodeExecutor(metis)
        } else {
            null
        }

    return DataPlane(
        worker = worker,
        inListMaterialiser =
            org.tatrman.kantheon.pythia.dataplane
                .DefaultInListMaterialiser(worker, materialiser),
        evidenceManager =
            org.tatrman.kantheon.pythia.dataplane
                .EvidenceManager(charon, policy, materialiser),
        model = model,
    )
}

private fun buildRepos(config: Config): RepoBundle {
    if (!config.getBoolean("pythia.db.enabled")) {
        log.info("pythia using in-memory repositories (pythia.db.enabled = false)")
        return RepoBundle(
            investigations = InMemoryInvestigationRepository(),
            hypotheses = InMemoryHypothesisRepository(),
            steps = InMemoryStepRepository(),
            handles = InMemoryHandleRepository(),
            checkpoints = InMemoryCheckpointRepository(),
            events = InMemoryEventRepository(),
            onStop = {},
        )
    }
    val database = PythiaDatabase(config)
    val migration = database.migrateAndConnect()
    log.info("pythia schema ready: version={} applied={}", migration.version, migration.applied)
    return RepoBundle(
        investigations = ExposedInvestigationRepository(database.connection),
        hypotheses = ExposedHypothesisRepository(database.connection),
        steps = ExposedStepRepository(database.connection),
        handles = ExposedHandleRepository(database.connection),
        checkpoints = ExposedCheckpointRepository(database.connection),
        events = ExposedEventRepository(database.connection),
        onStop = { database.close() },
    )
}

/** Uncaught-exception → 500 mapping with a typed body (avoids leaking stack traces). */
fun Application.installErrorPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on {}", call.request.local.uri, cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                buildJsonObject {
                    put("error", JsonPrimitive("internal_error"))
                    put("message", JsonPrimitive("Unexpected server error"))
                },
            )
        }
    }
}
