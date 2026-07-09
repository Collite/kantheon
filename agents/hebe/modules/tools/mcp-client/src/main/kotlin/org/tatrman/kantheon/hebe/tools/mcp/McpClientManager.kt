package org.tatrman.kantheon.hebe.tools.mcp

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.SecretLookup
import org.tatrman.kantheon.hebe.config.McpClientServerConfig
import org.tatrman.kantheon.hebe.tools.dispatch.ToolRegistry
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpClient
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory

sealed interface McpServerStatus {
    data object Connected : McpServerStatus

    data class Reconnecting(
        val attempt: Int,
        val nextRetryMs: Long,
    ) : McpServerStatus

    data class Failed(
        val reason: String,
    ) : McpServerStatus
}

class McpClientManager(
    private val registry: ToolRegistry,
    private val secretLookup: SecretLookup,
    private val monitorScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val connectedClients = ConcurrentHashMap<String, Client>()
    private val serverConfigs = ConcurrentHashMap<String, McpClientServerConfig>()
    private val serverStatuses = ConcurrentHashMap<String, McpServerStatus>()
    private val toolFilter = McpToolFilter()

    private val INITIAL_RETRY_MS = 5_000L
    private val MAX_RETRY_MS = 5 * 60 * 1000L // 5 minutes
    private val MAX_TOTAL_RETRY_MS = 60 * 60 * 1000L // 1 hour

    private val SENSITIVE_ENV_KEYS =
        setOf(
            "API_KEY",
            "TOKEN",
            "SECRET",
            "PASSWORD",
            "CREDENTIAL",
            "AUTH",
        )

    @Suppress("TooGenericExceptionCaught")
    suspend fun connect(serverConfigs: List<McpClientServerConfig>) {
        for (config in serverConfigs) {
            try {
                connectServerWithReconnect(config)
            } catch (e: RuntimeException) {
                logger.warn("Failed to connect to MCP server '{}': {}. Continuing startup.", config.name, e.message)
                serverStatuses[config.name] = McpServerStatus.Failed(e.message ?: "unknown error")
            }
        }
    }

    private suspend fun connectServerWithReconnect(config: McpClientServerConfig) {
        val startTime = System.currentTimeMillis()
        var attempt = 0
        var currentRetryMs = INITIAL_RETRY_MS

        while (true) {
            try {
                connectServer(config)
                serverStatuses[config.name] = McpServerStatus.Connected
                logger.info("MCP server '{}' connected successfully", config.name)
                launchMonitor(config)
                return
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime

                if (elapsed >= MAX_TOTAL_RETRY_MS) {
                    val message = "Giving up after ${MAX_TOTAL_RETRY_MS / 1000}s of retries: ${e.message}"
                    logger.error("MCP server '{}': {}", config.name, message)
                    serverStatuses[config.name] = McpServerStatus.Failed(message)
                    throw RuntimeException(message, e)
                }

                attempt++
                logger.warn(
                    "MCP server '{}' connection failed (attempt $attempt), retrying in ${currentRetryMs / 1000}s: {}",
                    config.name,
                    e.message,
                )
                serverStatuses[config.name] = McpServerStatus.Reconnecting(attempt, currentRetryMs)

                delay(currentRetryMs)

                // Exponential backoff with cap
                currentRetryMs = (currentRetryMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    private suspend fun connectServer(config: McpClientServerConfig) {
        logger.info("Connecting to MCP server '{}' via {}...", config.name, config.transport)

        val client =
            when (config.transport) {
                "stdio" -> connectStdio(config)
                else -> throw IllegalArgumentException("Unsupported transport: ${config.transport}")
            }

        connectedClients[config.name] = client
        serverConfigs[config.name] = config

        val toolsResult = client.listTools()
        logger.info("Server '{}' provides {} tools", config.name, toolsResult.tools.size)

        for (toolInfo in toolsResult.tools) {
            val toolName = "mcp_${config.name}_${toolInfo.name}"
            if (registry.get(toolName) != null) {
                logger.warn("Tool '{}' already exists, skipping (hebe wins)", toolName)
                continue
            }

            val remoteTool =
                RemoteTool(
                    name = toolName,
                    description = toolInfo.description ?: "",
                    inputSchema = toolInfo.inputSchema,
                    originalName = toolInfo.name,
                    mcpClient = client,
                    risk = RiskLevel.Medium,
                )
            registry.register(remoteTool)
            logger.debug("Registered remote tool: {}", toolName)
        }
    }

    fun toolsForMessage(
        serverName: String,
        userMessage: String,
    ): List<String> {
        val config = serverConfigs[serverName] ?: return emptyList()
        val allTools =
            registry
                .list()
                .filter { it.spec.name.startsWith("mcp_${serverName}_") }
                .map { it.spec.name }
        return toolFilter.applicableTools(config, allTools, userMessage)
    }

    private suspend fun connectStdio(config: McpClientServerConfig): Client {
        val secrets = buildEnvWithSecrets(config.envSecrets)
        val processBuilder =
            ProcessBuilder(config.command)
                .redirectErrorStream(true)

        processBuilder.environment().clear()
        processBuilder.environment().putAll(secrets)

        val process = processBuilder.start()

        val transport =
            StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
            )

        return mcpClient(
            clientInfo = Implementation(name = "hebe-mcp-client", version = "1.0.0"),
            transport = transport,
        )
    }

    internal fun buildEnvWithSecrets(
        envSecrets: Map<String, String>,
        systemEnv: Map<String, String> = System.getenv(),
    ): Map<String, String> {
        val env =
            systemEnv.filterKeys { envVar ->
                SENSITIVE_ENV_KEYS.none { sensitiveKey -> envVar.contains(sensitiveKey) }
            }
        val withSecrets =
            envSecrets.mapValues { (_, secretName) ->
                secretLookup.secret(secretName)
                    ?: error("Secret not found: $secretName")
            }
        return env + withSecrets
    }

    private fun launchMonitor(config: McpClientServerConfig) {
        monitorScope.launch {
            while (connectedClients.containsKey(config.name)) {
                delay(30_000L)
                val client = connectedClients[config.name] ?: break
                try {
                    client.listTools()
                } catch (e: Exception) {
                    logger.warn("MCP server '{}' monitor detected disconnect: {}", config.name, e.message)
                    connectedClients.remove(config.name)
                    registry
                        .list()
                        .filter { it.spec.name.startsWith("mcp_${config.name}_") }
                        .forEach { registry.unregister(it.spec.name) }
                    serverStatuses[config.name] = McpServerStatus.Reconnecting(0, INITIAL_RETRY_MS)
                    try {
                        connectServerWithReconnect(config)
                    } catch (re: RuntimeException) {
                        logger.error("MCP server '{}' failed to reconnect: {}", config.name, re.message)
                    }
                    break
                }
            }
        }
    }

    suspend fun disconnect(serverName: String) {
        val client = connectedClients.remove(serverName)
        if (client != null) {
            logger.info("Disconnecting MCP server '{}'", serverName)
            client.close()
            registry
                .list()
                .filter { it.spec.name.startsWith("mcp_${serverName}_") }
                .forEach { registry.unregister(it.spec.name) }
        }
    }

    suspend fun disconnectAll() {
        connectedClients.keys.toList().forEach { serverName ->
            disconnect(serverName)
        }
    }

    fun connectionStatus(): Map<String, McpServerStatus> = serverStatuses.toMap()
}
