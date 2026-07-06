package org.tatrman.kallimachos

import com.typesafe.config.ConfigFactory
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.kallimachos.http.adminRoutes
import org.tatrman.kallimachos.http.browseRoutes
import org.tatrman.kallimachos.http.loadRoutes
import org.tatrman.kallimachos.http.notebookRoutes
import org.tatrman.kallimachos.http.searchRoutes
import org.tatrman.kallimachos.ingestion.DocumentParser
import org.tatrman.kallimachos.retrieval.FusionConfig
import org.tatrman.kallimachos.retrieval.GraphWalk
import org.tatrman.kallimachos.retrieval.HybridFusion
import org.tatrman.kallimachos.retrieval.KeywordRecall
import org.tatrman.kallimachos.retrieval.VectorRecall
import org.tatrman.kallimachos.service.ContextService
import org.tatrman.kallimachos.service.DocumentQueryService
import org.tatrman.kallimachos.service.EmbeddingService
import org.tatrman.kallimachos.service.IngestionService
import org.tatrman.kallimachos.service.NotebookService
import org.tatrman.kallimachos.service.PageLoadService

private val log = LoggerFactory.getLogger("org.tatrman.kallimachos.Application")

/**
 * Bootstrap. Per `EXAMPLES.md` §1b the `App.kt` does only wiring.
 *
 * Two Ktor servers (contracts §11 — `kallimachos.{http.port=7261, probe.port=7260}`):
 *  - probe server (7260): `/health`, `/ready`, `/status`, `/metrics`.
 *  - API server (7261): the REST corpus surface.
 *
 * The corpus planes are selected by `kallimachos.storage.profile` ([CorpusStores]):
 * `memory` (P1 default — pod runs with no DB; ingest→query round-trips in-process)
 * or `postgres` (deploy, Stage 1.3). `/ready` reports 200 once the stores wire.
 */
fun main() {
    val config = ConfigFactory.load()
    val httpPort = config.getInt("kallimachos.http.port")
    val probePort = config.getInt("kallimachos.probe.port")

    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val stores = CorpusStores.fromConfig(config)
    log.info("Kallimachos corpus stores wired (profile={})", stores.profile)

    val ingestion =
        IngestionService(stores.relational, stores.fullText, stores.notebooks, stores.graph, stores.transactor)
    val notebooks = NotebookService(stores.notebooks)
    val queries = DocumentQueryService(stores.relational, stores.fullText, stores.notebooks)
    val embeddingService = EmbeddingService(stores.relational, stores.vector, stores.embeddings, stores.transactor)
    val pageLoad = PageLoadService(stores.relational, stores.graph, stores.notebooks, stores.transactor)

    val graphHops = config.getInt("kallimachos.retrieval.graph-hops")
    val k = config.getInt("kallimachos.retrieval.k")
    val fusion =
        HybridFusion(
            stores.relational,
            FusionConfig(
                graphWeight = config.getDouble("kallimachos.retrieval.graph-weight"),
                k = k,
                minScore = config.getDouble("kallimachos.retrieval.min-score"),
            ),
        )
    val context =
        ContextService(
            relational = stores.relational,
            notebooks = stores.notebooks,
            graph = stores.graph,
            graphWalk = GraphWalk(stores.graph, stores.relational),
            vectorRecall = VectorRecall(stores.vector, stores.embeddings),
            keywordRecall = KeywordRecall(stores.fullText),
            fusion = fusion,
            defaultK = k,
            defaultGraphHops = graphHops,
        )
    val parser = DocumentParser()

    embeddedServer(Netty, port = httpPort, host = "0.0.0.0", module = {
        apiModule(ingestion, notebooks, queries, context, embeddingService, pageLoad, parser)
    }).start(wait = false)
    log.info("Kallimachos REST API started on :{} (Stage 1.2 — ingest + keyword query + marts)", httpPort)

    embeddedServer(Netty, port = probePort, host = "0.0.0.0", module = {
        probeModule(meterRegistry, stores.profile)
    }).start(wait = true)
}

fun Application.apiModule(
    ingestion: IngestionService,
    notebooks: NotebookService,
    queries: DocumentQueryService,
    context: ContextService,
    embeddingService: EmbeddingService,
    pageLoad: PageLoadService,
    parser: DocumentParser,
) {
    install(ContentNegotiation) { json() }
    routing {
        searchRoutes(ingestion, queries, context, parser)
        notebookRoutes(notebooks)
        browseRoutes()
        loadRoutes(ingestion, pageLoad)
        adminRoutes(embeddingService)
    }
    monitor.subscribe(ApplicationStopping) { log.info("Kallimachos API shutting down") }
}

fun Application.probeModule(
    meterRegistry: PrometheusMeterRegistry,
    storageProfile: String,
) {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        // Ready once the corpus planes are wired (Stage 1.2). P2 adds the
        // vector/AGE extension checks to this gate.
        get("/ready") {
            call.respond(buildJsonObject { put("status", "UP"); put("stage", "1.2"); put("profile", storageProfile) })
        }
        get("/status") {
            call.respond(
                buildJsonObject {
                    put("service", "kallimachos")
                    put("stage", "1.2")
                    put("profile", storageProfile)
                    put("planes", "relational + fulltext (vector/graph → P2)")
                },
            )
        }
        get("/metrics") { call.respondText(meterRegistry.scrape(), ContentType.Text.Plain) }
    }
}
