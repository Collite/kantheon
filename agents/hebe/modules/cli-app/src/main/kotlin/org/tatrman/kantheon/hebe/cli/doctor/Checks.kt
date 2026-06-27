package org.tatrman.kantheon.hebe.cli.doctor

import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.config.SecretStoreProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path

sealed class CheckStatus {
    data object Pass : CheckStatus()

    data object Warn : CheckStatus()

    data object Fail : CheckStatus()
}

data class CheckResult(
    val name: String,
    val status: CheckStatus,
    val message: String,
    val hint: String? = null,
)

suspend fun runAllChecks(
    config: HebeConfig,
    secretStore: SecretStoreProvider,
    workspaceRoot: Path,
    observer: Observer,
): List<CheckResult> =
    coroutineScope {
        val checks =
            listOf(
                async { checkConfig(config) },
                async { checkLlmEndpoint(config, secretStore) },
                async { checkChannels(config) },
                async { checkKeychain(secretStore) },
                async { checkDatabase(workspaceRoot) },
                async { checkWorkspace(workspaceRoot) },
                async { checkReceiptsSigningKey(workspaceRoot, secretStore) },
                async { checkSandbox() },
                async { checkPlugins(workspaceRoot) },
                async { checkMcp(config) },
            )
        checks.awaitAll()
    }

private suspend fun checkConfig(config: HebeConfig): CheckResult {
    val issues = mutableListOf<String>()
    if (config.llm.baseUrl.isBlank()) issues.add("LLM base URL is empty")
    if (config.llm.apiKeySecret.isBlank()) issues.add("LLM API key secret is not set")
    if (config.llm.defaultModel.isBlank()) issues.add("Default model is not set")

    return if (issues.isEmpty()) {
        CheckResult("Config", CheckStatus.Pass, "Configuration is valid")
    } else {
        CheckResult(
            "Config",
            CheckStatus.Fail,
            issues.joinToString("; "),
            hint = "Check config.toml in ${System.getProperty("user.home")}/.hebe/",
        )
    }
}

private suspend fun checkLlmEndpoint(
    config: HebeConfig,
    secretStore: SecretStoreProvider,
): CheckResult {
    val apiKey = secretStore.get(config.llm.apiKeySecret)?.let { String(it, Charsets.UTF_8) } ?: ""
    if (apiKey.isBlank()) {
        return CheckResult(
            "LLM Endpoint",
            CheckStatus.Fail,
            "API key not found in keychain",
            hint = "Run 'hebe onboard' to configure LLM credentials",
        )
    }

    return try {
        val url = java.net.URL("${config.llm.baseUrl}/models")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        val responseCode = conn.responseCode
        conn.disconnect()
        if (responseCode in 200..299) {
            CheckResult("LLM Endpoint", CheckStatus.Pass, "LLM endpoint is reachable")
        } else {
            CheckResult(
                "LLM Endpoint",
                CheckStatus.Warn,
                "LLM endpoint returned HTTP $responseCode",
                hint = "Check base URL in config.toml",
            )
        }
    } catch (e: Exception) {
        CheckResult(
            "LLM Endpoint",
            CheckStatus.Fail,
            "Cannot reach LLM endpoint: ${e.message}",
            hint = "Verify base URL and network connectivity",
        )
    }
}

private fun checkChannels(config: HebeConfig): CheckResult {
    val channels = mutableListOf<String>()
    if (config.channels.web.enabled) channels.add("web")
    if (config.channels.telegram.enabled) channels.add("telegram")
    if (channels.isEmpty()) {
        return CheckResult(
            "Channels",
            CheckStatus.Warn,
            "No channels enabled",
            hint = "Enable at least one channel in config.toml",
        )
    }
    return CheckResult("Channels", CheckStatus.Pass, "Enabled: ${channels.joinToString(", ")}")
}

private suspend fun checkKeychain(secretStore: SecretStoreProvider): CheckResult {
    val llmKey = secretStore.get("llm.api_key")
    val webPassword = secretStore.get("web.password")

    return when {
        llmKey == null -> CheckResult("Keychain", CheckStatus.Fail, "LLM API key not stored", hint = "Run 'hebe onboard'")
        webPassword == null -> CheckResult("Keychain", CheckStatus.Warn, "Web password not stored", hint = "Run 'hebe onboard'")
        else -> CheckResult("Keychain", CheckStatus.Pass, "Credentials found in keychain")
    }
}

private fun checkDatabase(workspaceRoot: Path): CheckResult {
    val dbPath = workspaceRoot.resolve("hebe.db")
    if (!dbPath.toFile().exists()) {
        return CheckResult(
            "Database",
            CheckStatus.Fail,
            "hebe.db not found",
            hint = "Run 'hebe run' to initialize the database",
        )
    }
    try {
        val uri = "jdbc:sqlite:$dbPath"
        val connection = java.sql.DriverManager.getConnection(uri)
        try {
            val statement = connection.createStatement()
            statement.executeQuery("SELECT 1").use { rs -> rs.next() }

            statement
                .executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='flyway_schema_history'",
                ).use { rs ->
                    rs.next()
                    val hasFlyway = rs.getInt(1) > 0
                    if (hasFlyway) {
                        statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1").use { flywayRs ->
                            flywayRs.next()
                            val migrations = flywayRs.getInt(1)
                            return CheckResult(
                                "Database",
                                CheckStatus.Pass,
                                "hebe.db ready (flyway: $migrations migration(s) applied)",
                            )
                        }
                    }
                }
            return CheckResult("Database", CheckStatus.Pass, "hebe.db ready (no flyway tracking)")
        } finally {
            connection.close()
        }
    } catch (e: Exception) {
        return CheckResult(
            "Database",
            CheckStatus.Fail,
            "Database error: ${e.message}",
            hint = "Run 'hebe run' to re-initialize",
        )
    }
}

private fun checkWorkspace(workspaceRoot: Path): CheckResult {
    val requiredDirs = listOf("plugins", "receipts", "memory")
    val missing = requiredDirs.filter { !workspaceRoot.resolve(it).toFile().exists() }

    return if (missing.isEmpty()) {
        CheckResult("Workspace", CheckStatus.Pass, "All required directories exist")
    } else {
        CheckResult(
            "Workspace",
            CheckStatus.Fail,
            "Missing directories: ${missing.joinToString(", ")}",
            hint = "Run 'hebe run' to seed the workspace",
        )
    }
}

private suspend fun checkReceiptsSigningKey(
    workspaceRoot: Path,
    secretStore: SecretStoreProvider,
): CheckResult {
    val receiptsDir = workspaceRoot.resolve("receipts")
    val publicKey = receiptsDir.resolve("public.key")
    val privateKey = receiptsDir.resolve("private.key")

    return when {
        publicKey.toFile().exists() && privateKey.toFile().exists() ->
            CheckResult("Receipts Signing Key", CheckStatus.Pass, "Signing key pair exists")
        !publicKey.toFile().exists() -> CheckResult("Receipts Signing Key", CheckStatus.Fail, "Public key not found")
        else -> CheckResult("Receipts Signing Key", CheckStatus.Fail, "Private key not found")
    }
}

private fun checkSandbox(): CheckResult {
    val os = System.getProperty("os.name").lowercase()
    val isLinux = os.contains("linux")
    val isMac = os.contains("mac") || os.contains("darwin")

    if (!isLinux) {
        return if (isMac) {
            CheckResult("Sandbox", CheckStatus.Pass, "macOS detected - App Sandbox available")
        } else {
            CheckResult(
                "Sandbox",
                CheckStatus.Warn,
                "Windows - sandboxing limited",
                hint = "Consider using WSL2 on Windows",
            )
        }
    }

    val sandboxTools = listOf("firejail", "bwrap", "docker")
    val found = sandboxTools.filter { execExists(it) }

    return if (found.isNotEmpty()) {
        CheckResult("Sandbox", CheckStatus.Pass, "Found: ${found.joinToString(", ")}")
    } else {
        CheckResult(
            "Sandbox",
            CheckStatus.Warn,
            "No sandbox tools found (firejail, bwrap, docker)",
            hint = "Install firejail, bubblewrap, or docker for process isolation",
        )
    }
}

private fun execExists(cmd: String): Boolean =
    try {
        val process = Runtime.getRuntime().exec(arrayOf("which", cmd))
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }

private fun checkPlugins(workspaceRoot: Path): CheckResult {
    val pluginsDir = workspaceRoot.resolve("plugins")
    if (!pluginsDir.toFile().exists()) {
        return CheckResult("Plugins", CheckStatus.Pass, "No plugins directory yet")
    }
    val plugins = pluginsDir.toFile().listFiles()?.filter { it.isDirectory } ?: emptyList()
    return CheckResult("Plugins", CheckStatus.Pass, "${plugins.size} plugin(s) installed")
}

private fun checkMcp(config: HebeConfig?): CheckResult {
    if (config == null) {
        return CheckResult("MCP", CheckStatus.Warn, "Unknown (daemon not reachable)")
    }
    if (config.mcp.client.servers
            .isEmpty()
    ) {
        return CheckResult("MCP", CheckStatus.Pass, "No MCP servers configured")
    }
    return CheckResult("MCP", CheckStatus.Pass, "MCP servers in config (daemon state unknown)")
}

fun renderCheckTable(results: List<CheckResult>): String {
    val sb = StringBuilder()
    sb.appendLine("=== Hebe Doctor ===")
    sb.appendLine()
    sb.appendLine("%-20s %-6s %s".format("Check", "Status", "Message"))
    sb.appendLine("%-20s %-6s %s".format("-----", "------", "-------"))

    for (result in results) {
        val statusStr =
            when (result.status) {
                CheckStatus.Pass -> "PASS"
                CheckStatus.Warn -> "WARN"
                CheckStatus.Fail -> "FAIL"
            }
        sb.appendLine("%-20s %-6s %s".format(result.name, statusStr, result.message))
        result.hint?.let { sb.appendLine("  Hint: $it") }
    }
    return sb.toString()
}

fun renderCheckJson(results: List<CheckResult>): String {
    val checks =
        results.joinToString(",\n    ") { result ->
            val statusStr =
                when (result.status) {
                    CheckStatus.Pass -> "\"pass\""
                    CheckStatus.Warn -> "\"warn\""
                    CheckStatus.Fail -> "\"fail\""
                }
            val hintStr = result.hint?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
            """
            {
                "name": "${result.name}",
                "status": $statusStr,
                "message": "${result.message.replace("\"", "\\\"")}",
                "hint": $hintStr
            }
            """.trimIndent()
        }
    return """
        {
            "checks": [
                $checks
            ],
            "summary": {
                "pass": ${results.count { it.status == CheckStatus.Pass }},
                "warn": ${results.count { it.status == CheckStatus.Warn }},
                "fail": ${results.count { it.status == CheckStatus.Fail }}
            }
        }
        """.trimIndent()
}

fun hasAnyFailure(results: List<CheckResult>): Boolean = results.any { it.status == CheckStatus.Fail }
