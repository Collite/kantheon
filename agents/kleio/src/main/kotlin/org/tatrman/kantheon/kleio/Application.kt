package org.tatrman.kantheon.kleio

import com.google.protobuf.util.JsonFormat
import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.kleio.clients.HttpKallimachosMcpClient
import org.tatrman.kantheon.kleio.clients.HttpKleioLlmClient
import org.tatrman.kantheon.kleio.graph.ArtifactNode
import org.tatrman.kantheon.kleio.graph.KleioStrategy
import org.tatrman.kantheon.kleio.persistence.InMemoryKleioTurnsRepository
import org.tatrman.kantheon.kleio.v1.ArtifactRequest
import org.tatrman.kantheon.kleio.v1.ArtifactResponse
import org.tatrman.kantheon.kleio.v1.KleioRequest

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.kleio.Application")

/**
 * Bootstrap. The NotebookLM agent (architecture §4): a grounded mart turn over
 * `library.getContext` + Prometheus synthesis, emitting `envelope/v1` with the
 * §5 grounding contract. Mirrors the Golem bootstrap (trusts Themis upstream).
 */
fun main() {
    val config = ConfigFactory.load()
    val port = config.getInt("kleio.port")
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    val http = HttpClient(CIO)
    val mcpBase = "http://${config.getString(
        "kleio.kallimachos-mcp.host",
    )}:${config.getInt("kleio.kallimachos-mcp.port")}"
    val promBase = "http://${config.getString("kleio.prometheus.host")}:${config.getInt("kleio.prometheus.port")}"
    val grounded =
        KleioStrategy::class.java.classLoader
            .getResource("prompts/grounded-answer.md")!!
            .readText()

    val promModel = config.getString("kleio.prometheus.model")
    val retriever = HttpKallimachosMcpClient(http, mcpBase)
    val llm = HttpKleioLlmClient(http, promBase, grounded, promModel)
    val turnService =
        KleioTurnService(
            strategy = KleioStrategy(retriever, llm),
            turns = InMemoryKleioTurnsRepository(),
            k = config.getInt("kleio.retrieval.k"),
            minScore = config.getDouble("kleio.retrieval.min-score"),
        )
    val artifactNode = ArtifactNode(retriever, llm)

    val capEndpoint =
        System.getenv("CAPABILITIES_MCP_URL")
            ?: if (config.hasPath("capabilities-mcp.url")) config.getString("capabilities-mcp.url") else ""
    KleioRegistration.register(capEndpoint, "http://kleio:$port")

    embeddedServer(
        Netty,
        port = port,
        host = "0.0.0.0",
        module = { module(meterRegistry, turnService, artifactNode) },
    ).start(wait = true)
}

fun Application.module(
    meterRegistry: PrometheusMeterRegistry,
    turnService: KleioTurnService,
    artifactNode: ArtifactNode,
) {
    val printer = JsonFormat.printer().omittingInsignificantWhitespace()
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") { call.respond(buildJsonObject { put("status", "UP"); put("agent", "kleio") }) }
        get("/metrics") { call.respondText(meterRegistry.scrape(), ContentType.Text.Plain) }

        // The grounded mart turn (KleioRequest → GroundedResponse, proto-JSON).
        post("/v1/answer") {
            val bearer =
                call.request.headers[HttpHeaders.Authorization]
                    ?.removePrefix("Bearer ")
                    ?.trim()
            val builder = KleioRequest.newBuilder()
            JsonFormat.parser().ignoringUnknownFields().merge(call.receiveText(), builder)
            val response = turnService.answer(builder.build(), bearer)
            // Observability (architecture §13): kleio_turns_total{status} + grounded citations.
            meterRegistry.counter("kleio_turns_total", "status", response.status.name).increment()
            meterRegistry.summary("kleio_grounded_citations").record(response.sourcesUsedCount.toDouble())
            call.respondText(printer.print(response), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // Artifact generation (SUMMARY/FAQ/TIMELINE/BRIEFING) over a mart.
        post("/v1/artifact") {
            val bearer =
                call.request.headers[HttpHeaders.Authorization]
                    ?.removePrefix("Bearer ")
                    ?.trim()
            val builder = ArtifactRequest.newBuilder()
            JsonFormat.parser().ignoringUnknownFields().merge(call.receiveText(), builder)
            val req = builder.build()
            val outcome = artifactNode.generate(req.notebookId, req.kind, req.focus.takeIf { req.hasFocus() }, bearer)
            meterRegistry.counter("kleio_artifact_total", "kind", outcome.kind.name).increment()
            val resp =
                ArtifactResponse
                    .newBuilder()
                    .setArtifactId(outcome.artifactId)
                    .setEnvelope(outcome.envelope)
                    .addAllSourcesUsed(outcome.sourcesUsed)
                    .build()
            call.respondText(printer.print(resp), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
    log.info("Kleio agent started")
}
