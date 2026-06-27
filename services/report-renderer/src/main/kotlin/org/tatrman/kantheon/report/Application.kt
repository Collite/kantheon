package org.tatrman.kantheon.report

import com.google.protobuf.util.JsonFormat
import com.typesafe.config.ConfigFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.report.engine.DataFetcher
import org.tatrman.kantheon.report.engine.ReportData
import org.tatrman.kantheon.report.store.ArtifactStore
import org.tatrman.kantheon.report.template.RepoBundledResolver
import org.tatrman.kantheon.report.template.TemplateRegistry
import org.tatrman.kantheon.report.v1.ListTemplatesResponse
import org.tatrman.kantheon.report.v1.RenderReportRequest
import java.nio.file.Path

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.report.Application")

/**
 * Report-renderer bootstrap (Stage 3.4). Serves the template catalog + the synchronous
 * `/render` (XLSX) + the FS artifact download/delete (contracts §8). The live `DataFetcher`
 * (Midas-core MCP tools) + the PPTX/PDF/HTML engines are integration-deferred; v1 boot wires
 * an empty fetcher so the control surface + XLSX path stand up.
 */
fun main() {
    val config = ConfigFactory.load()
    val httpPort = config.getInt("report-renderer.http.port")
    val artifactDir =
        if (config.hasPath("report-renderer.artifacts.dir")) {
            config.getString("report-renderer.artifacts.dir")
        } else {
            "/var/midas/artifacts"
        }
    val artifacts = ArtifactStore(Path.of(artifactDir))
    val service =
        RenderService(
            resolver = RepoBundledResolver(),
            data = DataFetcher { _, _ -> ReportData() },
            artifacts = artifacts,
        )
    log.info("Report-renderer starting on :{} (artifacts → {})", httpPort, artifactDir)
    embeddedServer(Netty, port = httpPort, host = "0.0.0.0") { module(service, artifacts) }.start(wait = true)
}

fun Application.module(
    service: RenderService,
    artifacts: ArtifactStore,
) {
    val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    // A render that fails before producing an artifact rides back as a clean 400 with an error
    // body built through the JSON serializer (never string-interpolated — the message embeds caller
    // input). Anything else is logged and surfaces as a generic 500, not a stack-trace leak.
    install(StatusPages) {
        exception<RenderException> { call, e ->
            call.respondText(errorJson(e.code, e.message), ContentType.Application.Json, HttpStatusCode.BadRequest)
        }
        exception<Throwable> { call, e ->
            log.error("render request failed", e)
            call.respondText(
                errorJson("internal_error", "the render request could not be processed"),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
    routing {
        get("/health") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }
        get("/ready") { call.respondText("""{"status":"ready"}""", ContentType.Application.Json) }

        get("/templates") {
            val resp = ListTemplatesResponse.newBuilder().addAllTemplates(TemplateRegistry.ALL).build()
            call.respondText(printer.print(resp), ContentType.Application.Json)
        }
        get("/templates/{id}") {
            val t = call.parameters["id"]?.let { TemplateRegistry.byId(it) }
            if (t == null) {
                call.respondText("""{"error":"not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            } else {
                call.respondText(printer.print(t), ContentType.Application.Json)
            }
        }

        post("/render") {
            val raw = call.receiveText()
            val req =
                try {
                    RenderReportRequest
                        .newBuilder()
                        .also { JsonFormat.parser().ignoringUnknownFields().merge(raw, it) }
                        .build()
                } catch (e: Exception) {
                    log.debug("malformed /render body", e)
                    throw RenderException("invalid_json", "request body is not a valid RenderReportRequest")
                }
            // RenderException + any other failure are handled by StatusPages (400 / 500).
            call.respondText(printer.print(service.render(req)), ContentType.Application.Json)
        }

        get("/artifacts/{id}") {
            val bytes = call.parameters["id"]?.let { artifacts.read(it) }
            if (bytes == null) {
                call.respondText("""{"error":"not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            } else {
                call.respondBytes(bytes, ContentType.parse(RenderService.XLSX_MIME))
            }
        }
        delete("/artifacts/{id}") {
            val ok = call.parameters["id"]?.let { artifacts.delete(it) } ?: false
            call.respondText(
                """{"deleted":$ok}""",
                ContentType.Application.Json,
                if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound,
            )
        }
    }
}

/** Error body built through the JSON serializer so a caller-supplied message can't break the JSON. */
private fun errorJson(
    code: String,
    message: String?,
): String =
    buildJsonObject {
        put("error", code)
        put("message", message ?: code)
    }.toString()
