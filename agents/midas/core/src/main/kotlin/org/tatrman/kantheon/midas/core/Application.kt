package org.tatrman.kantheon.midas.core

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.midas.core.api.assetRoutes
import org.tatrman.kantheon.midas.core.api.clientRoutes
import org.tatrman.kantheon.midas.core.api.fxRateRoutes
import org.tatrman.kantheon.midas.core.api.installMidasErrorPages
import org.tatrman.kantheon.midas.core.api.portfolioRoutes
import org.tatrman.kantheon.midas.core.api.transactionRoutes
import org.tatrman.kantheon.midas.core.auth.BearerAuthenticator
import org.tatrman.kantheon.midas.core.infra.MidasDatabase
import org.tatrman.kantheon.midas.core.mcp.DbTransactionLog
import org.tatrman.kantheon.midas.core.mcp.MidasTools
import org.tatrman.kantheon.midas.core.mcp.registerWithCapabilities
import org.tatrman.kantheon.midas.core.mcp.startMidasMcpServer
import org.tatrman.kantheon.midas.core.repository.AssetRepository
import org.tatrman.kantheon.midas.core.repository.ClientRepository
import org.tatrman.kantheon.midas.core.repository.FxRateRepository
import org.tatrman.kantheon.midas.core.repository.PortfolioRepository
import org.tatrman.kantheon.midas.core.repository.PositionRepository
import org.tatrman.kantheon.midas.core.repository.TransactionRepository
import shared.ktor.mcp.McpTelemetry

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.midas.core.Application")

/**
 * Midas-core bootstrap (Stage 1.3). Migrates the operational DB on boot (fail
 * fast), wires the repositories + validate-only auth, and serves the REST write
 * API under `/api/v1`. The MCP tool surface (Stage 1.4) mounts on the same server.
 */
fun main() {
    val config = ConfigFactory.load()
    val httpPort = config.getInt("midas-core.http.port")

    val database = MidasDatabase(config)
    val migration = database.migrateAndConnect()
    log.info("midas-core DB ready: version={} applied={}", migration.version, migration.applied)

    val deps =
        MidasDeps(
            database = database,
            clients = ClientRepository(database.connection),
            portfolios = PortfolioRepository(database.connection),
            assets = AssetRepository(database.connection),
            fxRates = FxRateRepository(database.connection),
            transactions = TransactionRepository(database.connection),
            positions = PositionRepository(database.connection),
            auth =
                BearerAuthenticator(
                    tenantClaim = config.stringOr("midas-core.auth.tenant-claim", "tenant"),
                    defaultTenant = config.stringOr("midas-core.auth.default-tenant", "default"),
                    verifySignature = config.booleanOr("midas-core.auth.verify-signature", false),
                ),
        )

    // MCP tool surface (Stage 1.4) — a second CIO listener in the same process,
    // plus capabilities-mcp registration (both warn-and-continue).
    val mcpPort = if (config.hasPath("midas-core.mcp.port")) config.getInt("midas-core.mcp.port") else 7311
    val mcpTelemetry = McpTelemetry("midas-core", "rest")
    val mcpServer =
        startMidasMcpServer(
            mcpPort,
            MidasTools(deps.positions, DbTransactionLog(database.connection, deps.transactions)),
            mcpTelemetry,
        )
    registerWithCapabilities(config)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            mcpServer.stop(1_000, 2_000)
            database.close()
        },
    )
    log.info("Midas-core starting on :{} (REST) + :{} (MCP)", httpPort, mcpPort)
    embeddedServer(Netty, port = httpPort, host = "0.0.0.0") { module(deps) }.start(wait = true)
}

class MidasDeps(
    val database: MidasDatabase,
    val clients: ClientRepository,
    val portfolios: PortfolioRepository,
    val assets: AssetRepository,
    val fxRates: FxRateRepository,
    val transactions: TransactionRepository,
    val positions: PositionRepository,
    val auth: BearerAuthenticator,
)

fun Application.module(deps: MidasDeps) {
    installMidasErrorPages()
    routing {
        get("/health") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }
        get("/ready") {
            val ok =
                runCatching {
                    deps.database.connection.query {
                        TransactionManager.current().exec(
                            "SELECT 1",
                        )
                    }
                }.isSuccess
            if (ok) {
                call.respondText("""{"status":"ready"}""", ContentType.Application.Json)
            } else {
                call.respondText(
                    """{"status":"not_ready"}""",
                    ContentType.Application.Json,
                    io.ktor.http.HttpStatusCode.ServiceUnavailable,
                )
            }
        }
        route("/api/v1") {
            clientRoutes(deps.clients, deps.auth)
            portfolioRoutes(deps.portfolios, deps.auth)
            assetRoutes(deps.assets, deps.auth)
            fxRateRoutes(deps.fxRates, deps.auth)
            transactionRoutes(deps.transactions, deps.auth)
        }
    }
}

private fun Config.stringOr(
    path: String,
    default: String,
): String = if (hasPath(path)) getString(path) else default

private fun Config.booleanOr(
    path: String,
    default: Boolean,
): Boolean = if (hasPath(path)) getBoolean(path) else default
