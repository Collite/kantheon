package org.tatrman.kantheon.hebe.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.tomlj.Toml
import org.tomlj.TomlTable

sealed class ConfigResult<out T> {
    data class Ok<T>(
        val value: T,
        val diagnostics: List<ConfigDiagnostic>,
    ) : ConfigResult<T>()

    data class Error(
        val diagnostics: List<ConfigDiagnostic>,
    ) : ConfigResult<Nothing>()
}

data class ConfigDiagnostic(
    val level: DiagnosticLevel,
    val message: String,
    val source: String?,
    val line: Int?,
    val column: Int?,
)

enum class DiagnosticLevel {
    INFO,
    WARNING,
    ERROR,
}

const val DEFAUTL_EMBEDDING_DIM = 1536

@Suppress("detekt:TooManyFunctions", "detekt:ComplexCondition")
class ConfigLoader {
    /**
     * Resolves the [Axes] from `config.toml` + environment (P2 Stage 2.1;
     * contracts §5). A missing file resolves to the `local` preset (the
     * pre-profile default — no behaviour change). A present file is parsed and
     * resolved; a [ConfigValidationException] (unknown profile/axis, or a
     * cross-axis fail-fast) propagates — axis misconfiguration is boots-stopping.
     */
    fun loadAxes(
        path: Path,
        env: Map<String, String> = System.getenv(),
    ): Axes =
        if (Files.exists(path)) {
            AxesLoader.resolve(Toml.parse(path), env)
        } else {
            ProfileResolver.resolve(RawAxisConfig(envProfile = env["HEBE_PROFILE"]))
        }

    fun load(path: Path): ConfigResult<HebeConfig> {
        val diagnostics = mutableListOf<ConfigDiagnostic>()
        val tomlResult = tryParseToml(path, diagnostics) ?: return ConfigResult.Error(diagnostics)
        val config = parseConfig(tomlResult, diagnostics)
        return if (diagnostics.any { it.level == DiagnosticLevel.ERROR }) {
            ConfigResult.Error(diagnostics)
        } else {
            ConfigResult.Ok(config, diagnostics)
        }
    }

    private fun tryParseToml(
        path: Path,
        diagnostics: MutableList<ConfigDiagnostic>,
    ): TomlTable? =
        try {
            Toml.parse(path)
        } catch (e: IOException) {
            diagnostics.add(
                ConfigDiagnostic(
                    level = DiagnosticLevel.ERROR,
                    message = "Failed to read TOML file: ${e.message}",
                    source = path.toString(),
                    line = null,
                    column = null,
                ),
            )
            null
        }

    private fun parseConfig(
        toml: TomlTable,
        diagnostics: MutableList<ConfigDiagnostic>,
    ): HebeConfig {
        val hebe = parseHebe(toml.getTable("hebe"))
        val llm = parseLlm(toml.getTable("llm"), diagnostics)
        val autonomy = parseAutonomy(toml.getTable("autonomy"))
        val security = parseSecurity(toml.getTable("security"))
        val scheduler = parseScheduler(toml.getTable("scheduler"))
        val channels = parseChannels(toml.getTable("channels"))
        val plugins = parsePlugins(toml.getTable("plugins"))
        val mcp = parseMcp(toml.getTable("mcp"))
        return HebeConfig(
            hebe = hebe,
            llm = llm,
            autonomy = autonomy,
            security = security,
            scheduler = scheduler,
            channels = channels,
            plugins = plugins,
            mcp = mcp,
        )
    }

    private fun parseHebe(table: TomlTable?): HebeSection {
        if (table == null) return HebeSection()
        return HebeSection(
            dataDir = table.getString("data_dir") ?: "~/.hebe",
            logLevel = table.getString("log_level") ?: "info",
        )
    }

    private fun parseLlm(
        table: TomlTable?,
        diagnostics: MutableList<ConfigDiagnostic>,
    ): LlmSection {
        if (table == null) {
            diagnostics.add(
                ConfigDiagnostic(
                    level = DiagnosticLevel.ERROR,
                    message = "Missing required [llm] section",
                    source = null,
                    line = null,
                    column = null,
                ),
            )
            return LlmSection("", "", "", "", DEFAUTL_EMBEDDING_DIM)
        }
        val baseUrl = table.getString("base_url")
        val apiKeySecret = table.getString("api_key_secret")
        val defaultModel = table.getString("default_model")
        val embeddingModel = table.getString("embedding_model")
        if (baseUrl == null || apiKeySecret == null || defaultModel == null || embeddingModel == null) {
            diagnostics.add(
                ConfigDiagnostic(
                    level = DiagnosticLevel.ERROR,
                    message = "[llm] section requires base_url, api_key_secret, default_model, embedding_model",
                    source = null,
                    line = null,
                    column = null,
                ),
            )
        }
        return LlmSection(
            baseUrl = baseUrl ?: "",
            apiKeySecret = apiKeySecret ?: "",
            defaultModel = defaultModel ?: "",
            embeddingModel = embeddingModel ?: "",
            embeddingDim = table.getLong("embedding_dim")?.toInt() ?: DEFAUTL_EMBEDDING_DIM,
        )
    }

    private fun parseAutonomy(table: TomlTable?): AutonomySection {
        if (table == null) return AutonomySection()
        val levelStr = table.getString("level") ?: "Supervised"
        val level =
            try {
                org.tatrman.kantheon.hebe.api.AutonomyLevel
                    .valueOf(levelStr.replaceFirstChar { it.uppercase() })
            } catch (_: Exception) {
                org.tatrman.kantheon.hebe.api.AutonomyLevel.Supervised
            }
        return AutonomySection(level = level)
    }

    private fun parseSecurity(table: TomlTable?): SecuritySection {
        if (table == null) return SecuritySection()
        return SecuritySection(
            forbiddenPaths = getStringList(table, "forbidden_paths"),
            allowedCommandGlobs = getStringList(table, "allowed_command_globs"),
            forbiddenCommandGlobs = getStringList(table, "forbidden_command_globs"),
            httpAllowlistDomains = getStringList(table, "http_allowlist_domains"),
            pluginSignatureMode =
                table.getString("plugin_signature_mode")?.let {
                    try {
                        PluginSignatureMode.valueOf(it)
                    } catch (_: Exception) {
                        PluginSignatureMode.OPTIONAL
                    }
                } ?: PluginSignatureMode.OPTIONAL,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun getStringList(
        table: TomlTable,
        key: String,
    ): List<String> {
        val value = table.get(key)
        return if (value is java.util.List<*>) {
            (value as List<*>).filterIsInstance<String>()
        } else {
            emptyList()
        }
    }

    private fun getStringMap(
        table: TomlTable,
        key: String,
    ): Map<String, String> {
        val value = table.get(key)
        return if (value is TomlTable) {
            value.keySet().associateWith { k -> value.getString(k) ?: "" }
        } else {
            emptyMap()
        }
    }

    private fun parseScheduler(table: TomlTable?): SchedulerSection {
        if (table == null) return SchedulerSection()
        return SchedulerSection(
            heartbeatCron = table.getString("heartbeat_cron") ?: "0 */6 * * *",
            dailyDigestCron = table.getString("daily_digest_cron") ?: "5 0 * * *",
            summarisationCron = table.getString("summarisation_cron") ?: "*/30 * * * *",
            factExtractCron = table.getString("fact_extract_cron") ?: "10 * * * *",
        )
    }

    private fun parseChannels(table: TomlTable?): ChannelsSection {
        if (table == null) return ChannelsSection()
        val cliTable = table.getTable("cli")
        val webTable = table.getTable("web")
        val telegramTable = table.getTable("telegram")
        return ChannelsSection(
            cli = parseCliChannel(cliTable),
            web = parseWebChannel(webTable),
            telegram = parseTelegramChannel(telegramTable),
        )
    }

    private fun parseCliChannel(table: TomlTable?): CliChannelConfig {
        if (table == null) return CliChannelConfig()
        return CliChannelConfig(
            enabled = table.getBoolean("enabled") ?: true,
        )
    }

    private fun parseWebChannel(table: TomlTable?): WebChannelConfig {
        if (table == null) return WebChannelConfig()
        return WebChannelConfig(
            enabled = table.getBoolean("enabled") ?: true,
            bind = table.getString("bind") ?: "127.0.0.1",
            port = table.getLong("port")?.toInt() ?: 8765,
            adminPasswordSecret = table.getString("admin_password_secret") ?: "web.password",
        )
    }

    private fun parseTelegramChannel(table: TomlTable?): TelegramChannelConfig {
        if (table == null) return TelegramChannelConfig()
        return TelegramChannelConfig(
            enabled = table.getBoolean("enabled") ?: false,
            botTokenSecret = table.getString("bot_token_secret") ?: "telegram.bot_token",
            operatorTelegramId = table.getLong("operator_telegram_id") ?: 0L,
            chatUserMap = getStringMap(table, "chat_user_map"),
        )
    }

    private fun parsePlugins(table: TomlTable?): PluginsSection {
        if (table == null) return PluginsSection()
        return PluginsSection(
            registry = table.getString("registry") ?: "",
            autoPull = getStringList(table, "auto_pull"),
            publisherKeys = getStringList(table, "publisher_keys"),
        )
    }

    private fun parseMcp(table: TomlTable?): McpSection {
        if (table == null) return McpSection()
        val serverTable = table.getTable("server")
        val clientTable = table.getTable("client")
        return McpSection(
            server = parseMcpServer(serverTable),
            client = parseMcpClient(clientTable),
        )
    }

    private fun parseMcpServer(table: TomlTable?): McpServerConfig {
        if (table == null) return McpServerConfig()
        return McpServerConfig(
            enabled = table.getBoolean("enabled") ?: true,
            stdio = table.getBoolean("stdio") ?: true,
            httpBind = table.getString("http_bind") ?: "",
            httpPort = table.getLong("http_port")?.toInt() ?: 0,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMcpClient(table: TomlTable?): McpClientConfig {
        if (table == null) return McpClientConfig()
        val serversArray = table.getArray("servers")
        val serversList =
            if (serversArray != null) {
                (0 until serversArray.size())
                    .mapNotNull { serversArray.getTable(it) }
                    .mapNotNull { parseMcpClientServerFromTable(it) }
            } else {
                emptyList()
            }
        return McpClientConfig(servers = serversList)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMcpClientServerFromTable(t: TomlTable): McpClientServerConfig {
        val commandList = getStringList(t, "command")
        val envSecretsMap = getStringMap(t, "secrets")
        return McpClientServerConfig(
            name = t.getString("name") ?: "",
            transport = t.getString("transport") ?: "stdio",
            command = commandList,
            envSecrets = envSecretsMap,
            alwaysTools = getStringList(t, "always_tools"),
            dynamicTools = getStringList(t, "dynamic_tools"),
            dynamicKeywords = getStringList(t, "dynamic_keywords"),
        )
    }
}
