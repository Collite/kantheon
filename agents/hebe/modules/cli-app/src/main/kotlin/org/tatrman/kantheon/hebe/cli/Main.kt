@file:Suppress(
    "TooGenericExceptionCaught",
    "MagicNumber",
    "LongMethod",
    "EmptyFunctionBlock",
    "MaxLineLength",
)

package org.tatrman.kantheon.hebe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.tatrman.kantheon.hebe.cli.daemon.PidFile
import org.tatrman.kantheon.hebe.cli.daemon.Shutdown
import org.tatrman.kantheon.hebe.config.ConfigLoader
import org.tatrman.kantheon.hebe.config.ConfigResult
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.config.OsKeychainSecretStore
import org.tatrman.kantheon.hebe.gateway.Gateway
import org.tatrman.kantheon.hebe.observability.LogbackObserver
import org.tatrman.kantheon.hebe.observability.OtelBootstrap
import org.tatrman.kantheon.hebe.security.estop.EstopIpc
import org.tatrman.kantheon.hebe.security.receipts.ReceiptVerifier
import org.tatrman.kantheon.hebe.security.receipts.VerifyResult
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private fun loadConfigOrDefault(path: java.nio.file.Path): HebeConfig =
    if (java.nio.file.Files
            .exists(path)
    ) {
        org.tatrman.kantheon.hebe.config.ConfigLoader().load(path).let { result ->
            when (result) {
                is org.tatrman.kantheon.hebe.config.ConfigResult.Ok -> result.value
                is org.tatrman.kantheon.hebe.config.ConfigResult.Error -> {
                    System.err.println("Warning: failed to load config, using defaults")
                    HebeConfig.default()
                }
            }
        }
    } else {
        HebeConfig.default()
    }

fun main(args: Array<String>) {
    HebeCLI().main(args)
}

/**
 * GET `<baseUrl>/models` with the gateway bearer key, returning the advertised
 * model ids, or `null` if the gateway is unreachable / errored (P2 Stage 2.2
 * doctor probe). Best-effort + short timeout — never throws.
 */
private fun gatewayModels(
    baseUrl: String,
    apiKey: String,
): List<String>? =
    try {
        val conn = URI("$baseUrl/models").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.connect()
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            null
        } else {
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val data = Json.parseToJsonElement(body).jsonObject["data"]
            (data as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.get("id")?.let { id -> (id as? kotlinx.serialization.json.JsonPrimitive)?.content }
            } ?: emptyList()
        }
    } catch (_: Exception) {
        null
    }

class HebeCLI : CliktCommand(name = "hebe") {
    init {
        subcommands(
            RunCommand(),
            McpServeCommand(),
            PluginInstallCommand(),
            PluginListCommand(),
            PluginRemoveCommand(),
            DoctorCommand(),
            ServiceInstallCommand(),
            ServiceStartCommand(),
            ServiceStopCommand(),
            ServiceUninstallCommand(),
            ServiceStatusCommand(),
            StatusCommand(),
            CompletionBashCommand(),
            CompletionZshCommand(),
            CompletionFishCommand(),
            OnboardCommand(),
            EstopCommand(),
            MemoryShowCommand(),
        )
    }

    override fun run() {
        echo("Hebe v1.0.0")
        echo("Use --help for available commands")
    }
}

class RunCommand : CliktCommand(name = "run") {
    private val log = LoggerFactory.getLogger(RunCommand::class.java)

    override fun run() {
        val workspaceRoot = Path.of(System.getProperty("user.home"), ".hebe")
        Files.createDirectories(workspaceRoot)

        val configPath = workspaceRoot.resolve("config.toml")
        val config =
            if (Files.exists(configPath)) {
                when (val r = ConfigLoader().load(configPath)) {
                    is ConfigResult.Ok -> r.value
                    is ConfigResult.Error -> {
                        log.warn("Failed to load config, using defaults: {}", r.diagnostics)
                        HebeConfig.default()
                    }
                }
            } else {
                HebeConfig.default()
            }

        // Resolve the axis model (P2 Stage 2.1; contracts §5). Under `local`
        // (the default when no `profile` key is present) this reproduces the
        // previous behaviour exactly — subsystems still read these axes, not the
        // profile name. A misconfiguration (e.g. ephemeral FS without postgres)
        // fails fast here, before any component is built.
        val axes = ConfigLoader().loadAxes(configPath)
        log.info(
            "resolved profile axes: storage={} fs={} platform.reach={} llm={} tools.posture={} instance={}",
            axes.storage.backend.token,
            axes.fs.durability.token,
            axes.platform.reach.token,
            axes.llm.source.token,
            axes.tools.posture.token,
            axes.instanceId,
        )

        // P2 Stage 2.3 — the `security.secrets_backend` axis selects the secrets
        // store (keychain | file | k8s). `local` (keychain) is unchanged.
        val secretStore =
            org.tatrman.kantheon.hebe.config.SecretsStoreFactory
                .create(axes.security.secretsBackend, workspaceRoot)
        val logbackObserver = LogbackObserver()
        // P2 Stage 2.4 — OTel driven by the `otel.enabled` axis; false is a true
        // no-op (local/personal default). Service name is hebe-<instance_id>.
        val observer =
            OtelBootstrap.createObserver(
                logbackObserver,
                enabled = axes.otel.enabled,
                serviceName = "hebe-${axes.instanceId}",
            )

        log.info("building agent components")
        val components = AgentFactory.build(config, secretStore, workspaceRoot, observer, log, axes)

        val channelWiring =
            ChannelWiring(
                webChannel = components.webChannel,
                receiptsDir = workspaceRoot.resolve("receipts"),
            )

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        runBlocking {
            channelWiring.registerChannels(components.channelManager, components.telegramChannel)
        }
        components.channelManager.start(scope)
        components.scheduler?.start(scope)

        val pidFile = PidFile.acquire(workspaceRoot.resolve("hebe.pid"))
        Shutdown.installHook(scope, pidFile) {
            log.info("draining in-flight turns")
            components.shutdown()
        }

        log.info("starting gateway on {}:{}", config.channels.web.bind, config.channels.web.port)
        Gateway().start(
            config = config.channels.web,
            secretStore = secretStore,
            mcpDeps = null,
        ) { channelWiring.applyToGateway(this) }
    }
}

class McpServeCommand : CliktCommand(name = "mcp serve") {
    private val configPath =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "config.toml")

    override fun run() {
        val hebeConfig = loadConfigOrDefault(configPath)
        val mcpConfig = hebeConfig.mcp.server
        if (!mcpConfig.stdio) {
            echo("Stdio MCP server disabled in config")
            return
        }

        val registry =
            org.tatrman.kantheon.hebe.tools.dispatch
                .ToolRegistry()

        val workspacePath =
            java.nio.file.Path.of(
                hebeConfig.hebe.dataDir.replace("~", System.getProperty("user.home")),
            )
        org.tatrman.kantheon.hebe.mcp
            .registerMcpBuiltinTools(registry, workspacePath)

        val validators =
            org.tatrman.kantheon.hebe.security.policy.PolicyChain
                .standard(hebeConfig, workspacePath)

        val receiptsDir =
            java.nio.file.Paths
                .get(System.getProperty("user.home"), ".hebe", "receipts")
        val signingKey = loadOrCreateSigningKey(receiptsDir)
        val receipts =
            org.tatrman.kantheon.hebe.security.receipts
                .Receipts(receiptsDir, signingKey)

        // P2 Stage 2.4 — the MCP-server dispatch path is posture-gated too: a
        // remote MCP caller reaches the filesystem family, so it must enforce the
        // axis-resolved posture (k8s default restricted), not the unrestricted
        // fallback the lightweight dispatcher previously used.
        val axes = ConfigLoader().loadAxes(configPath)
        val postureGate = AgentFactory.postureGate(axes)
        val dispatcher =
            org.tatrman.kantheon.hebe.mcp.McpDispatcherFactory
                .createLightweightDispatcher(registry, validators, receipts, postureGate)

        echo("Starting Hebe MCP Server (stdio mode)...")
        runBlocking<Unit> {
            org.tatrman.kantheon.hebe.mcp
                .runMcpStdioServer(hebeConfig, registry, dispatcher)
        }
    }

    private fun loadOrCreateSigningKey(receiptsDir: java.nio.file.Path): org.tatrman.kantheon.hebe.security.receipts.Ed25519PrivateKey {
        val keyFile = receiptsDir.resolve("private.key")
        return if (keyFile.exists()) {
            val bytes = Base64.getDecoder().decode(keyFile.readText().trim())
            org.tatrman.kantheon.hebe.security.receipts.Ed25519PrivateKey
                .load(bytes)
        } else {
            java.nio.file.Files
                .createDirectories(receiptsDir)
            val key =
                org.tatrman.kantheon.hebe.security.receipts.Ed25519PrivateKey
                    .generate()
            java.nio.file.Files
                .writeString(keyFile, Base64.getEncoder().encodeToString(key.encode()))
            key
        }
    }
}

class PluginInstallCommand : CliktCommand(name = "plugin install") {
    private val refOrPath by argument(help = "OCI reference (e.g. ghcr.io/user/hello-plugin:0.1.0) or local path to .zip file")
    private val unsigned by option("--unsigned", help = "Skip signature verification (for local/sideloaded plugins)").flag()
    private val configPath =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "config.toml")
    private val hebeConfig: org.tatrman.kantheon.hebe.config.HebeConfig by lazy {
        loadConfigOrDefault(configPath)
    }
    private val pluginsDir =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "plugins")
    private val settingsStore: org.tatrman.kantheon.hebe.config.SettingsStore =
        org.tatrman.kantheon.hebe.config
            .defaultSettingsStore()

    override fun run() {
        val input = refOrPath
        if (input.isBlank()) {
            echo("Error: plugin reference or path required")
            return
        }
        val isFile =
            java.nio.file.Files
                .exists(
                    java.nio.file.Path
                        .of(input),
                ) &&
                java.nio.file.Path
                    .of(input)
                    .toFile()
                    .isFile
        val verifier =
            org.tatrman.kantheon.hebe.plugins.signature.SignatureVerifier(
                signatureMode = hebeConfig.security.pluginSignatureMode,
                trustedPublisherKeys = hebeConfig.plugins.publisherKeys,
                log = org.slf4j.LoggerFactory.getLogger("plugin-install"),
            )
        if (isFile) {
            val path =
                java.nio.file.Path
                    .of(input)
            echo("Installing plugin from local file: $path")
            runBlocking {
                val sideloadFlow =
                    org.tatrman.kantheon.hebe.plugins.install.SideloadFlow(
                        signatureVerifier = verifier,
                        pluginsDir = pluginsDir,
                        log = org.slf4j.LoggerFactory.getLogger("plugin-install"),
                    )
                val result = sideloadFlow.sideload(path, unsigned)
                when (result) {
                    is org.tatrman.kantheon.hebe.plugins.install.InstallResult.Ok -> {
                        runBlocking { settingsStore.setInstalledPlugin(result.name, result.version, source = "sideload") }
                        echo("Installed: ${result.name}")
                        echo("Location: ${result.extractDir}")
                    }
                    is org.tatrman.kantheon.hebe.plugins.install.InstallResult.Error -> {
                        echo("Error: ${result.message}")
                        throw com.github.ajalt.clikt.core
                            .Abort()
                    }
                }
            }
        } else {
            val registryHost = hebeConfig.plugins.registry.takeIf { it.isNotBlank() } ?: "ghcr.io"
            val fullRef = if (input.contains("/")) input else "$registryHost/$input"
            echo("Installing plugin from: $fullRef")
            runBlocking {
                val secretStore =
                    object : org.tatrman.kantheon.hebe.config.SecretStoreProvider {
                        override suspend fun get(key: String): ByteArray? = null

                        override suspend fun set(
                            key: String,
                            value: ByteArray,
                        ) {}

                        override suspend fun delete(key: String): Boolean = false

                        override suspend fun list(): List<String> = emptyList()
                    }
                val ociClient =
                    org.tatrman.kantheon.hebe.plugins.oci.OciClient(
                        registry = registryHost,
                        secretStore = secretStore,
                        log = org.slf4j.LoggerFactory.getLogger("plugin-install"),
                    )
                val installFlow =
                    org.tatrman.kantheon.hebe.plugins.install.InstallFlow(
                        ociClient = ociClient,
                        signatureVerifier = verifier,
                        pluginsDir = pluginsDir,
                        log = org.slf4j.LoggerFactory.getLogger("plugin-install"),
                    )
                val result = installFlow.install(fullRef)
                when (result) {
                    is org.tatrman.kantheon.hebe.plugins.install.InstallResult.Ok -> {
                        runBlocking { settingsStore.setInstalledPlugin(result.name, result.version, source = fullRef) }
                        echo("Installed: ${result.name}")
                        echo("Location: ${result.extractDir}")
                    }
                    is org.tatrman.kantheon.hebe.plugins.install.InstallResult.Error -> {
                        echo("Error: ${result.message}")
                        throw com.github.ajalt.clikt.core
                            .Abort()
                    }
                }
            }
        }
    }
}

class PluginListCommand : CliktCommand(name = "plugin list") {
    private val pluginsDir =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "plugins")
    private val settingsStore: org.tatrman.kantheon.hebe.config.SettingsStore =
        org.tatrman.kantheon.hebe.config
            .defaultSettingsStore()

    override fun run() {
        runBlocking {
            val plugins = settingsStore.getInstalledPlugins()
            if (plugins.isEmpty()) {
                echo("No plugins installed")
                return@runBlocking
            }
            echo("")
            echo("%-18s %-8s %-12s %-20s".format("ID", "VERSION", "STATUS", "CAPABILITIES"))
            echo("%-18s %-8s %-12s %-20s".format("--", "-------", "------", "------------"))
            for (plugin in plugins.sortedBy { it.name }) {
                val pluginPath = pluginsDir.resolve("${plugin.name}-${plugin.version}")
                val status = resolvePluginStatus(pluginPath, plugin.source)
                val caps = resolvePluginCapabilities(pluginPath)
                echo("%-18s %-8s %-12s %-20s".format(plugin.name, plugin.version, status, caps))
            }
            echo("")
            echo("${plugins.size} plugin(s) installed")
        }
    }

    private fun resolvePluginStatus(
        pluginPath: java.nio.file.Path,
        source: String?,
    ): String {
        val exists = pluginPath.toFile().exists()
        if (!exists) {
            return "NOT_INSTALLED"
        }
        return if (source == "sideload") {
            "SIDELOADED"
        } else {
            "INSTALLED"
        }
    }

    private fun resolvePluginCapabilities(pluginPath: java.nio.file.Path): String {
        val exists = pluginPath.toFile().exists()
        if (!exists) {
            return "-"
        }
        val capsVal = readPluginCapabilities(pluginPath)
        return if (capsVal != null) {
            capsVal.capabilities.joinToString(",") { it.name.lowercase() }
        } else {
            "-"
        }
    }

    private fun readPluginCapabilities(pluginPath: java.nio.file.Path): org.tatrman.kantheon.hebe.plugin.api.PluginManifest? {
        val tomlPath = pluginPath.resolve("plugin.toml")
        val exists = tomlPath.toFile().exists()
        if (!exists) {
            return null
        }
        return try {
            val parser = org.tatrman.kantheon.hebe.plugins.manifest.ManifestParser
            val result = parser.parse(tomlPath)
            if (result is org.tatrman.kantheon.hebe.plugins.manifest.ManifestResult.Ok) {
                result.value
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

class PluginRemoveCommand : CliktCommand(name = "plugin remove") {
    private val pluginsDir =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "plugins")
    private val settingsStore: org.tatrman.kantheon.hebe.config.SettingsStore =
        org.tatrman.kantheon.hebe.config
            .defaultSettingsStore()
    private val name by argument(help = "Plugin name (e.g. hello-plugin-0.1.0)")

    override fun run() {
        val pluginPath = pluginsDir.resolve(name)
        if (!java.nio.file.Files
                .exists(pluginPath)
        ) {
            echo("Error: plugin '$name' not found at $pluginPath")
            return
        }
        try {
            java.nio.file.Files
                .walk(pluginPath)
                .sorted(Comparator.reverseOrder())
                .map { it.toFile() }
                .forEach { it.delete() }
            runBlocking { settingsStore.removeInstalledPlugin(name) }
            echo("Removed plugin: $name")
        } catch (e: Exception) {
            echo("Error removing plugin: ${e.message}")
        }
    }
}

class DoctorCommand : CliktCommand(name = "doctor") {
    private val jsonOutput by option("--json", help = "Output results as JSON").flag()
    private val verbose by option("--verbose", help = "Include recent events dump").flag()

    override fun run() {
        val workspaceRoot = Path.of(System.getProperty("user.home"), ".hebe")
        val configPath = workspaceRoot.resolve("config.toml")

        val config =
            if (Files.exists(configPath)) {
                when (val r = ConfigLoader().load(configPath)) {
                    is ConfigResult.Ok -> r.value
                    is ConfigResult.Error -> {
                        echo("Warning: failed to load config: ${r.diagnostics}")
                        HebeConfig.default()
                    }
                }
            } else {
                HebeConfig.default()
            }

        // Resolve axes first so doctor reports the active profile + the required-
        // vs-probed split (contracts §5.3) and so OTel honours `otel.enabled`
        // (a true no-op when false, like the serving path).
        val axes = ConfigLoader().loadAxes(configPath)
        val requirement =
            org.tatrman.kantheon.hebe.cli.doctor.AxisAwareDoctor
                .platformRequirement(axes)

        val secretStore =
            org.tatrman.kantheon.hebe.config.SecretsStoreFactory
                .create(axes.security.secretsBackend, workspaceRoot)
        val logbackObserver = LogbackObserver()
        val observer =
            OtelBootstrap.createObserver(
                logbackObserver,
                enabled = axes.otel.enabled,
                serviceName = "hebe-${axes.instanceId}",
            )
        if (!jsonOutput) {
            echo("Profile axes — platform.reach=${axes.platform.reach.token}, availability=${axes.platform.availability?.token ?: "—"}")
            echo("Platform dependencies are ${requirement.name.lowercase()} under this profile.")
            echo("")
        }

        val results =
            kotlinx.coroutines.runBlocking {
                val base =
                    org.tatrman.kantheon.hebe.cli.doctor.runAllChecks(
                        config = config,
                        secretStore = secretStore,
                        workspaceRoot = workspaceRoot,
                        observer = observer,
                    )
                // P2 Stage 2.2 — axis-gated gateway checks (only when llm.source
                // reaches the gateway). Probes hit [llm].base_url/models; the
                // required-vs-probed demotion is applied inside the specs.
                val gatewayKey =
                    org.tatrman.kantheon.hebe.config.SecretRef
                        .resolve(config.llm.apiKeySecret, secretStore) ?: ""
                val gatewaySpecs =
                    org.tatrman.kantheon.hebe.cli.doctor.GatewayChecks
                        .specs(
                            defaultModel = config.llm.defaultModel,
                            reach = { gatewayModels(config.llm.baseUrl, gatewayKey) != null },
                            models = { gatewayModels(config.llm.baseUrl, gatewayKey) ?: emptyList() },
                        )
                val gatewayResults =
                    org.tatrman.kantheon.hebe.cli.doctor.AxisAwareDoctor
                        .planChecks(axes, gatewaySpecs)
                        .map { it.probe(axes) }
                // Demote the platform-reaching base checks (e.g. the gateway-bound
                // LLM endpoint) under intermittent/none so an offline `personal`
                // host does not fail `doctor` (contracts §5.3).
                org.tatrman.kantheon.hebe.cli.doctor.AxisAwareDoctor
                    .demoteBaseChecks(axes, base) + gatewayResults
            }

        if (jsonOutput) {
            echo(
                org.tatrman.kantheon.hebe.cli.doctor
                    .renderCheckJson(results),
            )
        } else {
            echo(
                org.tatrman.kantheon.hebe.cli.doctor
                    .renderCheckTable(results),
            )
            if (verbose) {
                val events = logbackObserver.recentEvents(50)
                if (events.isNotEmpty()) {
                    echo("")
                    echo("--- Recent Events (last 50) ---")
                    events.forEach { echo(it.toString()) }
                }
            }
        }

        if (org.tatrman.kantheon.hebe.cli.doctor
                .hasAnyFailure(results)
        ) {
            throw com.github.ajalt.clikt.core
                .Abort()
        }
    }
}

class ServiceInstallCommand : CliktCommand(name = "service install") {
    private val jar by option("--jar", help = "Path to hebe.jar").default("")
    private val java by option("--java", help = "Path to java binary").default(
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse("java"),
    )

    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        val svc =
            org.tatrman.kantheon.hebe.cli.service
                .platformService(dataDir)
        val jarPath =
            jar.ifEmpty {
                val devJar = Path.of(System.getProperty("user.dir"), "modules", "cli-app", "build", "libs", "hebe.jar")
                if (Files.exists(devJar)) {
                    devJar.toString()
                } else {
                    echo("Error: --jar not specified and hebe.jar not found at $devJar")
                    return
                }
            }
        val result = svc.install(jarPath, java)
        if (result.isSuccess) {
            echo("Service installed. Run `hebe service start` to start it.")
            echo("Note (Linux): run `loginctl enable-linger \$USER` to survive logouts.")
        } else {
            echo("Error: ${result.exceptionOrNull()?.message}")
        }
    }
}

class ServiceStartCommand : CliktCommand(name = "service start") {
    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        val result =
            org.tatrman.kantheon.hebe.cli.service
                .platformService(dataDir)
                .start()
        if (result.isSuccess) echo("Service started.") else echo("Error: ${result.exceptionOrNull()?.message}")
    }
}

class ServiceStopCommand : CliktCommand(name = "service stop") {
    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        val result =
            org.tatrman.kantheon.hebe.cli.service
                .platformService(dataDir)
                .stop()
        if (result.isSuccess) echo("Service stopped.") else echo("Error: ${result.exceptionOrNull()?.message}")
    }
}

class ServiceUninstallCommand : CliktCommand(name = "service uninstall") {
    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        val result =
            org.tatrman.kantheon.hebe.cli.service
                .platformService(dataDir)
                .uninstall()
        if (result.isSuccess) echo("Service uninstalled.") else echo("Error: ${result.exceptionOrNull()?.message}")
    }
}

class ServiceStatusCommand : CliktCommand(name = "service status") {
    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        val status =
            org.tatrman.kantheon.hebe.cli.service
                .platformService(dataDir)
                .status()
        val (label, desc) =
            when (status) {
                is org.tatrman.kantheon.hebe.cli.service.ServiceStatus.Running -> "running" to "Hebe service is running"
                is org.tatrman.kantheon.hebe.cli.service.ServiceStatus.Stopped -> "stopped" to "Hebe service is stopped"
                is org.tatrman.kantheon.hebe.cli.service.ServiceStatus.NotInstalled -> "not-installed" to "Hebe service is not installed"
            }
        echo("$label: $desc")
    }
}

class StatusCommand : CliktCommand(name = "status") {
    private val url by option("--url", help = "Gateway base URL").default("http://127.0.0.1:8765")
    private val password by option("--password", help = "Admin password (plaintext)")
    private val recent by option("--recent", help = "Show last 20 receipts").flag()
    private val watch by option("--watch", help = "Refresh every 2s").flag()

    override fun run() {
        if (watch) {
            while (true) {
                print("[2J[H")
                printStatus()
                Thread.sleep(2_000)
            }
        } else {
            printStatus()
            if (recent) printReceipts()
        }
    }

    private fun printStatus() {
        try {
            val conn = URI("$url/api/status").toURL().openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            if (password != null) {
                val token = Base64.getEncoder().encodeToString("admin:$password".toByteArray())
                conn.setRequestProperty("Authorization", "Basic $token")
            }
            conn.connect()
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().readText()
                printStatusTable(json)
            } else {
                echo("HTTP ${conn.responseCode}")
            }
            conn.disconnect()
        } catch (e: Exception) {
            echo("Cannot reach gateway at $url: ${e.message}")
        }
    }

    private fun printReceipts() {
        try {
            val conn = URI("$url/api/receipts?limit=20").toURL().openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            if (password != null) {
                val token = Base64.getEncoder().encodeToString("admin:$password".toByteArray())
                conn.setRequestProperty("Authorization", "Basic $token")
            }
            conn.connect()
            if (conn.responseCode == 200) {
                echo("\n--- Recent receipts ---")
                echo(conn.inputStream.bufferedReader().readText())
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun printStatusTable(json: String) {
        try {
            val doc = Json.parseToJsonElement(json)
            val obj: JsonObject = doc.jsonObject
            echo("")
            echo("%-24s %s".format("Property", "Value"))
            echo("%-24s %s".format("--------", "-----"))
            obj.forEach { (key, value) ->
                echo("%-24s %s".format(key, value.toString().removeSurrounding("\"")))
            }
        } catch (_: Exception) {
            echo(json)
        }
    }
}

class CompletionBashCommand : CliktCommand(name = "completion bash") {
    override fun run() {
        echo(buildCompletionScript("bash"))
    }
}

class CompletionZshCommand : CliktCommand(name = "completion zsh") {
    override fun run() {
        echo(buildCompletionScript("zsh"))
    }
}

class CompletionFishCommand : CliktCommand(name = "completion fish") {
    override fun run() {
        echo(buildCompletionScript("fish"))
    }
}

private fun ask(
    label: String,
    default: String = "",
): String {
    val hint = if (default.isNotEmpty()) " [$default]" else ""
    print("$label$hint: ")
    System.out.flush()
    val line = readLine()?.trim() ?: ""
    return line.ifEmpty { default }
}

private fun askSecret(label: String): String {
    val console = System.console()
    return if (console != null) {
        String(console.readPassword("$label: ") ?: charArrayOf())
    } else {
        print("$label: ")
        System.out.flush()
        readLine()?.trim() ?: ""
    }
}

private val SUBCOMMANDS =
    listOf(
        "run",
        "mcp serve",
        "plugin install",
        "plugin list",
        "plugin remove",
        "doctor",
        "service install",
        "service start",
        "service stop",
        "service uninstall",
        "status",
        "completion bash",
        "completion zsh",
        "completion fish",
        "onboard",
        "estop",
        "memory show",
    )

private fun buildCompletionScript(shell: String): String {
    val dollar = "$"
    val subcmds = SUBCOMMANDS.joinToString(" ")
    return when (shell) {
        "bash" ->
            """
            _hebe_completions() {
                local cur="$dollar{COMP_WORDS[${dollar}COMP_CWORD]}"
                local subcommands="$subcmds"
                COMPREPLY=( ${'$'}(compgen -W "${dollar}subcommands" -- "${dollar}cur") )
            }
            complete -F _hebe_completions hebe
            """.trimIndent()
        "zsh" ->
            """
            #compdef hebe
            _hebe() {
                local subcmds=(${SUBCOMMANDS.joinToString(" ") { "\"$it\"" }})
                _describe 'command' subcmds
            }
            _hebe
            """.trimIndent()
        "fish" -> {
            val topLevel = SUBCOMMANDS.map { it.split(" ").first() }.distinct()
            val notSeen = "not __fish_seen_subcommand_from ${topLevel.joinToString(" ")}"
            val topLines = topLevel.joinToString("\n") { "complete -c hebe -f -n '$notSeen' -a $it" }
            val subLines =
                SUBCOMMANDS
                    .filter { " " in it }
                    .joinToString("\n") { sub ->
                        val parts = sub.split(" ", limit = 2)
                        "complete -c hebe -f -n '__fish_seen_subcommand_from ${parts[0]}' -a ${parts[1]}"
                    }
            "$topLines\n$subLines"
        }
        else -> ""
    }
}

class OnboardCommand : CliktCommand(name = "onboard") {
    private val force by option("--force", help = "Re-run even if already configured").flag()
    private val nonInteractive by option(
        "--non-interactive",
        help = "Read configuration from environment variables: HEBE_LLM_BASE_URL, HEBE_API_KEY, HEBE_ADMIN_PASSWORD, HEBE_DEFAULT_MODEL, HEBE_TELEGRAM_TOKEN, HEBE_OPERATOR_ID",
    ).flag()

    @Suppress("LongMethod", "ComplexMethod")
    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        Files.createDirectories(dataDir)
        val configPath = dataDir.resolve("config.toml")
        val bootstrapPath = dataDir.resolve("BOOTSTRAP.md")

        if (!force &&
            org.tatrman.kantheon.hebe.cli.onboard
                .isAlreadyOnboarded(configPath, bootstrapPath)
        ) {
            echo("Already onboarded. Use --force to reconfigure.")
            return
        }

        val profile: String
        val llmBaseUrl: String
        val apiKey: String
        val defaultModel: String
        val adminPassword: String
        val enableTelegram: Boolean
        val botToken: String
        val operatorId: Long

        if (nonInteractive) {
            profile = System.getenv("HEBE_PROFILE") ?: "local"
            llmBaseUrl = System.getenv("HEBE_LLM_BASE_URL") ?: run {
                echo("Error: HEBE_LLM_BASE_URL not set")
                return
            }
            apiKey = System.getenv("HEBE_API_KEY") ?: run {
                echo("Error: HEBE_API_KEY not set")
                return
            }
            adminPassword = System.getenv("HEBE_ADMIN_PASSWORD") ?: run {
                echo("Error: HEBE_ADMIN_PASSWORD not set")
                return
            }
            defaultModel = System.getenv("HEBE_DEFAULT_MODEL") ?: "gpt-4o-mini"
            val tgToken = System.getenv("HEBE_TELEGRAM_TOKEN") ?: ""
            botToken = tgToken
            enableTelegram = tgToken.isNotEmpty()
            operatorId = System.getenv("HEBE_OPERATOR_ID")?.toLongOrNull() ?: 0L
        } else {
            echo("=== Hebe Onboarding Wizard ===")
            echo("")

            // Step 0: Profile — the first question (P2 Stage 2.1). It selects the
            // axis-default bundle (contracts §5). "local" keeps the self-hosted
            // single-machine path unchanged.
            val chosenProfile = ask("Profile (local | personal | server | k8s)", "local")
            profile =
                if (org.tatrman.kantheon.hebe.config.Profile
                        .byToken(chosenProfile) != null
                ) {
                    chosenProfile
                } else {
                    echo("Unknown profile '$chosenProfile' — defaulting to 'local'")
                    "local"
                }

            // Step 1: LLM endpoint
            var tmpUrl = ""
            var tmpKey = ""
            while (true) {
                tmpUrl = ask("LLM base URL", "https://api.openai.com/v1")
                tmpKey = askSecret("API key")
                echo("Validating LLM endpoint...")
                if (org.tatrman.kantheon.hebe.cli.onboard
                        .validateLlmEndpoint(tmpUrl, tmpKey)
                ) {
                    echo("OK")
                    break
                }
                echo("Warning: endpoint did not respond with 200. Continue anyway? [y/N]")
                if (readLine()?.trim()?.lowercase() != "y") continue
                break
            }
            llmBaseUrl = tmpUrl
            apiKey = tmpKey

            // Step 2: Default model
            defaultModel = ask("Default model", "gpt-4o-mini")

            // Step 3: Admin password
            adminPassword = askSecret("Web admin password")
            val confirmPassword = askSecret("Confirm admin password")
            if (adminPassword != confirmPassword) {
                echo("Passwords do not match. Aborting.")
                return
            }

            // Step 4: Telegram (optional)
            echo("Enable Telegram channel? [y/N]")
            val tgEnabled = readLine()?.trim()?.lowercase() == "y"
            var tmpToken = ""
            var tmpOperatorId = 0L
            if (tgEnabled) {
                while (true) {
                    tmpToken = askSecret("Telegram bot token")
                    echo("Validating bot token...")
                    if (org.tatrman.kantheon.hebe.cli.onboard
                            .validateTelegramToken(tmpToken)
                    ) {
                        echo("OK")
                        break
                    }
                    echo("Invalid token. Retry? [y/N]")
                    if (readLine()?.trim()?.lowercase() != "y") break
                }
                tmpOperatorId = ask("Your Telegram user ID (numeric)", "0").trim().toLongOrNull() ?: 0L
            }
            enableTelegram = tgEnabled
            botToken = tmpToken
            operatorId = tmpOperatorId
        }

        val answers =
            org.tatrman.kantheon.hebe.cli.onboard.OnboardAnswers(
                llmBaseUrl = llmBaseUrl,
                apiKey = apiKey,
                defaultModel = defaultModel,
                adminPassword = adminPassword,
                telegramEnabled = enableTelegram && botToken.isNotEmpty(),
                telegramBotToken = botToken,
                operatorTelegramId = operatorId,
                profile = profile,
            )

        // Step 5: Write config
        echo("Writing config.toml...")
        val toml =
            org.tatrman.kantheon.hebe.cli.onboard
                .generateConfigToml(answers, dataDir)
        Files.writeString(configPath, toml)

        // Step 6: Persist secrets
        echo("Storing secrets...")
        val secretStore = OsKeychainSecretStore.create(dataDir)
        runBlocking {
            secretStore.set("llm.api_key", apiKey.toByteArray())
            secretStore.set("web.password", MessageDigest.getInstance("SHA-256").digest(adminPassword.toByteArray()))
            if (enableTelegram && botToken.isNotEmpty()) {
                secretStore.set("telegram.bot_token", botToken.toByteArray())
            }
        }

        // Step 7: Seed workspace + signing key
        echo("Seeding workspace...")
        val workspaceFs =
            org.tatrman.kantheon.hebe.memory.workspace
                .WorkspaceFs(dataDir)
        org.tatrman.kantheon.hebe.memory.workspace.WorkspaceSeeder
            .seedIfMissing(workspaceFs, dataDir)
        runBlocking {
            org.tatrman.kantheon.hebe.security.receipts.SigningKey
                .bootstrap(secretStore)
        }

        // Step 8: Finalise
        Files.deleteIfExists(bootstrapPath)
        echo("")
        echo("All set! Run `hebe doctor` to verify, then `hebe run` to start.")
    }
}

class EstopCommand : CliktCommand(name = "estop") {
    override fun run() {
        val socketPath =
            EstopIpc.getSocketPath(
                Paths.get(System.getProperty("user.home"), ".hebe"),
            )
        echo("Sending estop to local hebe instance…")
        val ok = EstopIpc.sendStop(socketPath)
        if (ok) {
            echo("Acknowledged. In-flight tool calls cancelled. Pending approvals expired.")
        } else {
            echo("Error: could not reach hebe instance at $socketPath — is it running?")
        }
    }
}

class MemoryShowCommand : CliktCommand(name = "memory show") {
    private val pathArg by argument(help = "Path to receipts directory or file")

    override fun run() {
        val path = Paths.get(pathArg)
        val publicKeyPath = Paths.get(System.getProperty("user.home"), ".hebe", "receipts", "public.key")

        if (!path.exists()) {
            echo("Error: Path not found: $path")
            return
        }

        val publicKeyBytes =
            if (publicKeyPath.exists()) {
                java.util.Base64
                    .getDecoder()
                    .decode(publicKeyPath.readText().trim())
            } else {
                echo("Error: Public key not found at $publicKeyPath")
                return
            }

        val verifier = ReceiptVerifier()
        val result =
            if (path.toFile().isDirectory) {
                verifier.verifyDirectory(path, publicKeyBytes)
            } else {
                verifier.verify(path, publicKeyBytes)
            }

        when (result) {
            is VerifyResult.Ok -> {
                echo("OK ${result.records} records, last hash: ${result.lastSelfHash}")
            }
            is VerifyResult.Failed -> {
                echo("FAILED at record ${result.recordSeq}: ${result.reason}")
            }
        }
    }
}
