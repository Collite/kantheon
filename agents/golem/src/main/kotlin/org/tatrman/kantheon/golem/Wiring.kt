package org.tatrman.kantheon.golem

import com.typesafe.config.Config
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.api.AnswerService
import org.tatrman.kantheon.golem.api.Readiness
import org.tatrman.kantheon.golem.api.ShemAdmission
import org.tatrman.kantheon.golem.context.GolemModelSubsystem
import org.tatrman.kantheon.golem.execution.MiniPlanExecutor
import org.tatrman.kantheon.golem.execution.SelectionResolver
import org.tatrman.kantheon.golem.execution.QueryQueryClient
import org.tatrman.kantheon.golem.format.FormatConfig
import org.tatrman.kantheon.golem.format.FormatEnricher
import org.tatrman.kantheon.golem.format.LlmTopupChips
import org.tatrman.kantheon.golem.resume.ResumeCodec
import org.tatrman.kantheon.golem.graph.GolemGraphDeps
import org.tatrman.kantheon.golem.persistence.ExposedTurnsRepository
import org.tatrman.kantheon.golem.persistence.GolemDatabase
import org.tatrman.kantheon.golem.persistence.InMemoryTurnsRepository
import org.tatrman.kantheon.golem.persistence.TurnsRepository
import org.tatrman.kantheon.golem.plan.PlanComposer
import org.tatrman.kantheon.golem.plan.PlanValidator
import org.tatrman.kantheon.golem.shem.ShemRegistration
import org.tatrman.llm.client.LlmGatewayClient
import org.tatrman.llm.client.LlmGatewayEndpoint
import org.tatrman.llm.client.LlmGatewayPromptExecutor

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.Wiring")

/** The dev/test placeholder resume secret — rejected in DB-backed deploys (see buildAnswerSurface). */
private const val DEV_RESUME_SECRET = "dev-secret-change-in-production"

/** The wired-up components for a running golem pod (keeps Application.kt thin).
 *  Grows the Koog graph + query execution as Stages 2.3–2.4 land. */
class GolemComponents(
    val turns: TurnsRepository,
    val model: GolemModelSubsystem,
    val registration: ShemRegistration?,
    val admission: ShemAdmission?,
    val answer: AnswerService?,
    val readiness: Readiness,
    val onStop: () -> Unit,
) {
    /** Boot-time load: pull model + prompts, then register the Shem (all warn-and-continue). */
    suspend fun bootLoad() {
        model.load()
        registration?.start()
    }
}

/** The PD-8 admission gate + the turn service, built when a Shem is configured. */
private class AnswerSurface(
    val admission: ShemAdmission,
    val answer: AnswerService,
    val close: () -> Unit,
)

/**
 * Assemble the `/v1/answer/sync` surface — composer (LLM gateway) + validator +
 * executor (query-mcp) wired into the graph, plus the Shem admission gate. Null
 * unless a Shem + prompt store are loaded (skeleton boot has no answer surface).
 */
private fun buildAnswerSurface(
    config: Config,
    model: GolemModelSubsystem,
    turns: TurnsRepository,
): AnswerSurface? {
    val shem = model.shem ?: return null
    val promptStore = model.promptStore ?: return null

    val llmClient =
        LlmGatewayClient(
            LlmGatewayEndpoint(
                host = config.getString("golem.llm-gateway.host"),
                port = config.getInt("golem.llm-gateway.port"),
                timeoutMs = config.getLong("golem.llm-gateway.timeout-ms"),
                apiKey = config.getString("golem.llm-gateway.key"),
            ),
        )
    val promptExecutor = LlmGatewayPromptExecutor(llmClient)
    val queryClient = QueryQueryClient(url = config.getString("golem.query.url"))
    val formatConfig =
        FormatConfig(
            chartOnCompare = config.getBoolean("golem.format.chartOnCompare"),
            chipMinBeforeTopup = config.getInt("golem.format.chipMinBeforeTopup"),
            chipLlmTopupEnabled = config.getBoolean("golem.format.chipLlmTopupEnabled"),
            chipTopupTimeoutMs = config.getLong("golem.format.chipTopupTimeoutMs"),
        )
    // Chip top-up via a CHEAP completion (generic "mini" tier); failures degrade to no extra chips.
    val llmTopup =
        LlmTopupChips(formatConfig) { prompt -> llmClient.complete(prompt, model = "mini").getOrNull() }
    val formatEnricher = FormatEnricher(formatConfig, llmTopup)
    val deps =
        GolemGraphDeps(
            composer = PlanComposer(promptExecutor, promptStore),
            validator = PlanValidator(),
            miniPlanExecutor = MiniPlanExecutor(queryClient, formatEnricher = formatEnricher),
            promptExecutor = promptExecutor,
            selectionResolver = SelectionResolver(turns),
        )
    val secret = config.getString("golem.resume.hmacSecret")
    // Fail fast rather than sign resume tokens with the publicly-known dev placeholder in a
    // DB-backed (production-shaped) deployment. Local boot + tests run the in-memory store
    // (golem.db.enabled = false) and may keep the default; a Postgres-backed pod must not.
    if (config.getBoolean("golem.db.enabled") && (secret.isBlank() || secret == DEV_RESUME_SECRET)) {
        error(
            "golem.resume.hmacSecret must be overridden (GOLEM_RESUME_HMAC_SECRET) in a DB-backed " +
                "deployment — refusing to sign resume tokens with the dev placeholder",
        )
    }
    val resumeCodec =
        ResumeCodec(
            secret = secret,
            ttlSeconds = config.getLong("golem.resume.ttlSeconds"),
        )
    return AnswerSurface(
        admission = ShemAdmission(shem),
        answer = AnswerService(deps, model.packageContext, turns, resumeCodec),
        close = { llmClient.close() },
    )
}

/**
 * `/ready` gate: DB migrated (fail-fast at boot, so [dbReady] is true once we get
 * here) AND — when a Shem is configured — its model + prompts are loaded.
 */
class GolemReadiness(
    private val dbReady: Boolean,
    private val model: GolemModelSubsystem,
) : Readiness {
    override fun isReady(): Boolean = dbReady && model.isReady
}

/**
 * Select the turns repository from config: Postgres (Exposed + Flyway) when
 * `golem.db.enabled`, else the in-memory store (local boot / tests). The DB path
 * runs migrations synchronously at boot and **fails fast** if they don't apply —
 * the pod never reaches a serving state with an unmigrated schema, so `/ready`
 * returning true implies migrations completed.
 */
fun buildComponents(config: Config): GolemComponents {
    val model = GolemModelSubsystem.fromConfig(config)
    val registration =
        model.shem?.let { ShemRegistration(it, ShemRegistration.endpointFrom(config)) }

    if (!config.getBoolean("golem.db.enabled")) {
        log.info("golem using in-memory turns repository (golem.db.enabled = false)")
        val turns = InMemoryTurnsRepository()
        val surface = buildAnswerSurface(config, model, turns)
        return GolemComponents(
            turns = turns,
            model = model,
            registration = registration,
            admission = surface?.admission,
            answer = surface?.answer,
            readiness = GolemReadiness(dbReady = true, model = model),
            onStop = {
                surface?.close?.invoke()
                model.close()
            },
        )
    }

    val database = GolemDatabase(config)
    val migration = database.migrateAndConnect()
    log.info("golem schema ready: version={} applied={}", migration.version, migration.applied)
    val turns = ExposedTurnsRepository(database.connection)
    val surface = buildAnswerSurface(config, model, turns)
    return GolemComponents(
        turns = turns,
        model = model,
        registration = registration,
        admission = surface?.admission,
        answer = surface?.answer,
        readiness = GolemReadiness(dbReady = true, model = model),
        onStop = {
            surface?.close?.invoke()
            model.close()
            database.close()
        },
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
