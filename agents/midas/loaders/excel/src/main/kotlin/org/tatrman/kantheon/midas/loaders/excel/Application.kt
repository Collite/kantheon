package org.tatrman.kantheon.midas.loaders.excel

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.midas.loaders.excel.api.BearerAuthenticator
import org.tatrman.kantheon.midas.loaders.excel.api.installLoaderErrorPages
import org.tatrman.kantheon.midas.loaders.excel.api.loaderRoutes
import org.tatrman.kantheon.midas.loaders.excel.client.HttpMidasCoreClient
import org.tatrman.kantheon.midas.loaders.excel.parser.BrokerRegistry
import org.tatrman.kantheon.midas.loaders.excel.service.LoaderService
import org.tatrman.kantheon.midas.loaders.excel.storage.BlobJanitor
import org.tatrman.kantheon.midas.loaders.excel.storage.FsBlobStore
import org.tatrman.kantheon.midas.loaders.excel.store.InMemoryLoaderRunStore
import java.io.File
import java.time.Duration

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.midas.loaders.excel.Application")

/**
 * Excel loader bootstrap (Stage 1.5). Wires the parser/mapper + the loader-run store
 * + blob store + the Midas-core client, and serves the upload → preview → commit
 * surface under `/api/v1` (contracts §4.1). Transactions are written through
 * Midas-core's REST API (OBO bearer), never directly to the DB.
 */
fun main() {
    val config = ConfigFactory.load()
    val httpPort = config.getInt("excel-loader.http.port")
    val baseUrl = config.getString("excel-loader.midas-core.base-url")
    val blobDir = config.getString("excel-loader.blob.dir")

    val httpClient = HttpClient(CIO)
    val blobStore = FsBlobStore(File(blobDir))
    val service =
        LoaderService(
            registry = BrokerRegistry.load(),
            client = HttpMidasCoreClient(baseUrl, httpClient),
            store = InMemoryLoaderRunStore(),
            blobStore = blobStore,
        )
    val auth =
        BearerAuthenticator(
            tenantClaim = config.stringOr("excel-loader.auth.tenant-claim", "tenant"),
            defaultTenant = config.stringOr("excel-loader.auth.default-tenant", "default"),
        )

    val janitor =
        BlobJanitor(
            store = blobStore,
            ttl = Duration.ofHours(config.longOr("excel-loader.blob.ttl-hours", 24)),
            interval = Duration.ofMinutes(config.longOr("excel-loader.blob.sweep-interval-minutes", 60)),
        ).also { it.start() }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            janitor.stop()
            httpClient.close()
        },
    )
    log.info("Excel loader starting on :{} (midas-core={}, blobs={})", httpPort, baseUrl, blobDir)
    embeddedServer(Netty, port = httpPort, host = "0.0.0.0") { module(service, auth) }.start(wait = true)
}

fun Application.module(
    service: LoaderService,
    auth: BearerAuthenticator,
) {
    installLoaderErrorPages()
    routing {
        get("/health") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }
        get("/ready") { call.respondText("""{"status":"ready"}""", ContentType.Application.Json) }
        route("/api/v1") { loaderRoutes(service, auth) }
    }
}

private fun Config.stringOr(
    path: String,
    default: String,
): String = if (hasPath(path)) getString(path) else default

private fun Config.longOr(
    path: String,
    default: Long,
): Long = if (hasPath(path)) getLong(path) else default
