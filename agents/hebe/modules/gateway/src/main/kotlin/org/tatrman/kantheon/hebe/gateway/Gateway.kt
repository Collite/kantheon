package org.tatrman.kantheon.hebe.gateway

import org.tatrman.kantheon.hebe.config.McpServerConfig
import org.tatrman.kantheon.hebe.config.SecretStoreProvider
import org.tatrman.kantheon.hebe.config.WebChannelConfig
import org.tatrman.kantheon.hebe.mcp.installMcpHttpTransport
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import org.tatrman.kantheon.hebe.tools.dispatch.ToolRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.basic
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

data class McpDeps(
    val config: McpServerConfig,
    val registry: ToolRegistry,
    val dispatcher: ToolDispatcher,
)

class Gateway {
    private val logger = LoggerFactory.getLogger(javaClass)

    private var channelHealthProvider: () -> List<Pair<String, org.tatrman.kantheon.hebe.api.ChannelHealth>> = { emptyList() }
    private var llmHealthProvider: () -> Pair<String, Boolean> = { "" to false }
    private var serverStartMs: Long = 0L

    fun setChannelHealthProvider(provider: () -> List<Pair<String, org.tatrman.kantheon.hebe.api.ChannelHealth>>) {
        channelHealthProvider = provider
    }

    fun setLlmHealthProvider(provider: () -> Pair<String, Boolean>) {
        llmHealthProvider = provider
    }

    fun start(
        config: WebChannelConfig,
        secretStore: SecretStoreProvider,
        mcpDeps: McpDeps?,
        configureRoutes: Routing.() -> Unit,
    ) {
        logger.info("starting gateway on {}:{}", config.bind, config.port)
        serverStartMs = System.currentTimeMillis()

        embeddedServer(Netty, host = config.bind, port = config.port) {
            configureApplication(
                secretStore,
                config.adminPasswordSecret,
                "http://${config.bind}:${config.port}",
                mcpDeps,
                configureRoutes,
            )
        }.start(wait = true)
    }

    internal fun Application.configureApplication(
        secretStore: SecretStoreProvider,
        passwordSecret: String = "web.password",
        allowedOrigin: String = "http://localhost:8765",
        mcpDeps: McpDeps? = null,
        configureRoutes: Routing.() -> Unit,
    ) {
        install(CORS) {
            allowHost(allowedOrigin.removePrefix("http://").removePrefix("https://"))
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowHeader("Last-Event-ID")
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
        }
        installAuth(secretStore, passwordSecret)

        mcpDeps?.let { deps ->
            val mcpServer = org.tatrman.kantheon.hebe.mcp.createHebeMcpServer()
            installMcpHttpTransport(mcpServer, deps.config, deps.registry, deps.dispatcher, "gateway-http")
        }

        routing {
            serveStaticFile("/", "index.html", ContentType.Text.Html)
            serveStaticFile("/static/style.css", "style.css", ContentType.Text.CSS)
            serveStaticFile("/static/app.js", "app.js", ContentType.Application.JavaScript)
            serveStaticFile("/static/receipts.js", "receipts.js", ContentType.Application.JavaScript)
            serveStaticFile("/static/memory.js", "memory.js", ContentType.Application.JavaScript)
            get("/health") { call.respondText("OK") }
            authenticate("admin") {
                route("/api") {
                    configureRoutes()
                    get("/status") {
                        val channels = channelHealthProvider()
                        val status =
                            buildJsonObject {
                                put("uptimeMs", JsonPrimitive(System.currentTimeMillis() - serverStartMs))
                                put(
                                    "channels",
                                    buildJsonArray {
                                        channels.forEach { (name, health) ->
                                            add(
                                                buildJsonObject {
                                                    put("name", JsonPrimitive(name))
                                                    put("health", JsonPrimitive(health.name))
                                                },
                                            )
                                        }
                                    },
                                )
                                val (llmEndpoint, llmReachable) = llmHealthProvider()
                                put(
                                    "llm",
                                    buildJsonObject {
                                        if (llmEndpoint.isNotEmpty()) put("endpoint", JsonPrimitive(llmEndpoint))
                                        put("reachable", JsonPrimitive(llmReachable))
                                    },
                                )
                            }
                        call.respondText(status.toString(), contentType = ContentType.Application.Json)
                    }
                }
            }
        }
    }

    private fun Route.serveStaticFile(
        path: String,
        filename: String,
        contentType: ContentType,
    ) {
        get(path) {
            val resourceAsStream = javaClass.classLoader.getResourceAsStream("static/$filename")
            if (resourceAsStream != null) {
                val content = resourceAsStream.bufferedReader().use { it.readText() }
                call.respondText(content, contentType = contentType)
            } else {
                call.respondText("$filename not found", status = HttpStatusCode.NotFound)
            }
        }
    }

    private fun Application.installAuth(
        secretStore: SecretStoreProvider,
        passwordSecret: String,
    ) {
        authentication {
            basic("admin") {
                validate { credentials ->
                    val storedHash = secretStore.get(passwordSecret)
                    if (storedHash != null) {
                        val inputHash =
                            MessageDigest
                                .getInstance("SHA-256")
                                .digest(credentials.password.toByteArray())
                        if (MessageDigest.isEqual(inputHash, storedHash)) {
                            UserIdPrincipal(credentials.name)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        }
    }
}
