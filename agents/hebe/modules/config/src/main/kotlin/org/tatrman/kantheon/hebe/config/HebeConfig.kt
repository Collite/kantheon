package org.tatrman.kantheon.hebe.config

import kotlinx.serialization.Serializable

@Serializable
data class HebeConfig(
    val hebe: HebeSection,
    val llm: LlmSection,
    val autonomy: AutonomySection,
    val security: SecuritySection,
    val scheduler: SchedulerSection,
    val channels: ChannelsSection,
    val plugins: PluginsSection,
    val mcp: McpSection,
    val cost: CostSection = CostSection(),
) {
    companion object {
        fun default(): HebeConfig =
            HebeConfig(
                hebe = HebeSection(),
                llm = LlmSection("", "", "", ""),
                autonomy = AutonomySection(),
                security = SecuritySection(),
                scheduler = SchedulerSection(),
                channels = ChannelsSection(),
                plugins = PluginsSection(),
                mcp = McpSection(),
            )
    }
}

@Serializable
data class HebeSection(
    val dataDir: String = "~/.hebe",
    val logLevel: String = "info",
)

@Serializable
data class LlmSection(
    val baseUrl: String,
    val apiKeySecret: String,
    val defaultModel: String,
    val embeddingModel: String,
    val embeddingDim: Int = 1536,
)

@Serializable
data class AutonomySection(
    val level: org.tatrman.kantheon.hebe.api.AutonomyLevel = org.tatrman.kantheon.hebe.api.AutonomyLevel.Supervised,
)

@Serializable
data class SecuritySection(
    val forbiddenPaths: List<String> = emptyList(),
    val allowedCommandGlobs: List<String> = emptyList(),
    val forbiddenCommandGlobs: List<String> = emptyList(),
    val httpAllowlistDomains: List<String> = emptyList(),
    val pluginSignatureMode: PluginSignatureMode = PluginSignatureMode.OPTIONAL,
)

@Serializable
enum class PluginSignatureMode {
    OPTIONAL,
    REQUIRED,
    DISABLED,
}

@Serializable
data class SchedulerSection(
    val heartbeatCron: String = "0 */6 * * *",
    val dailyDigestCron: String = "5 0 * * *",
    val summarisationCron: String = "*/30 * * * *",
    val factExtractCron: String = "10 * * * *",
)

@Serializable
data class CostSection(
    val dailyUsdCap: Double = 5.0,
    val perTurnTokenCap: Int = 100_000,
    val compactionThreshold: Double = 0.6,
)

@Serializable
data class ChannelsSection(
    val cli: CliChannelConfig = CliChannelConfig(),
    val web: WebChannelConfig = WebChannelConfig(),
    val telegram: TelegramChannelConfig = TelegramChannelConfig(),
)

@Serializable
data class CliChannelConfig(
    val enabled: Boolean = true,
)

@Serializable
data class WebChannelConfig(
    val enabled: Boolean = true,
    val bind: String = "127.0.0.1",
    val port: Int = 8765,
    val adminPasswordSecret: String = "web.password",
)

@Serializable
data class TelegramChannelConfig(
    val enabled: Boolean = false,
    val botTokenSecret: String = "telegram.bot_token",
    val operatorTelegramId: Long = 0,
    /**
     * Maps a Telegram `chat_id` to a Keycloak user (P2 Stage 2.3 T5). Consulted
     * by the channel-identity guard when `security.platform_identity = keycloak`;
     * empty on identity-less profiles (the `operator_telegram_id` allowlist
     * applies instead). From `[channels.telegram.chat_user_map]` in config.toml.
     */
    val chatUserMap: Map<String, String> = emptyMap(),
)

@Serializable
data class PluginsSection(
    val registry: String = "",
    val autoPull: List<String> = emptyList(),
    val publisherKeys: List<String> = emptyList(),
)

@Serializable
data class McpSection(
    val server: McpServerConfig = McpServerConfig(),
    val client: McpClientConfig = McpClientConfig(),
)

@Serializable
data class McpServerConfig(
    val enabled: Boolean = true,
    val stdio: Boolean = true,
    val httpBind: String = "",
    val httpPort: Int = 0,
    val exposeHighRisk: Boolean = false,
)

@Serializable
data class McpClientConfig(
    val servers: List<McpClientServerConfig> = emptyList(),
)

@Serializable
data class McpClientServerConfig(
    val name: String,
    val transport: String = "stdio",
    val command: List<String> = emptyList(),
    val envSecrets: Map<String, String> = emptyMap(),
    val alwaysTools: List<String> = emptyList(),
    val dynamicTools: List<String> = emptyList(),
    val dynamicKeywords: List<String> = emptyList(),
)
