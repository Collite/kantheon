@file:Suppress(
    "TooGenericExceptionCaught",
    "MagicNumber",
    "UnusedPrivateProperty",
    "LongParameterList",
    "LongMethod",
    "NewLineAtEndOfFile",
    "MaxLineLength",
)

package org.tatrman.kantheon.hebe.plugins

import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.api.security.ArgsRedactor
import org.tatrman.kantheon.hebe.plugin.api.HebePlugin
import org.tatrman.kantheon.hebe.plugin.api.PluginManifest
import org.tatrman.kantheon.hebe.plugins.abi.AbiChecker
import org.tatrman.kantheon.hebe.plugins.abi.AbiResult
import org.tatrman.kantheon.hebe.plugins.host.HostFactory
import org.tatrman.kantheon.hebe.plugins.manifest.ManifestError
import org.tatrman.kantheon.hebe.plugins.manifest.ManifestParser
import org.tatrman.kantheon.hebe.plugins.manifest.ManifestResult
import org.tatrman.kantheon.hebe.plugins.signature.SignatureResult
import org.tatrman.kantheon.hebe.plugins.signature.SignatureVerifier
import java.nio.file.Path
import org.pf4j.PluginWrapper
import org.slf4j.Logger

open class Lifecycle(
    private val pluginManager: org.pf4j.PluginManager,
    private val pluginDir: Path,
    private val toolRegistry: ToolRegistryWrapper,
    private val hostFactory: HostFactory,
    private val signatureVerifier: SignatureVerifier,
    private val observer: Observer,
    private val pluginStore: PluginRegistrationStore,
    private val secretResolver: (String) -> String?,
    private val redactor: ArgsRedactor = ArgsRedactor.INSTANCE,
    private val log: Logger,
) {
    open fun afterStart(pluginWrapper: PluginWrapper) {
        val pluginId = pluginWrapper.pluginId
        log.info("Starting plugin '{}'", pluginId)

        val manifestResult = loadManifest(pluginWrapper)
        if (manifestResult is ManifestResult.Error) {
            log.error("Plugin '{}' failed to load manifest: {}", pluginId, manifestResult.errors)
            stopPlugin(pluginWrapper)
            return
        }

        val manifest = (manifestResult as ManifestResult.Ok).value

        val archiveHash = computeArchiveHash(pluginWrapper)
        val sigResult = signatureVerifier.verify(manifest, archiveHash)
        when (sigResult) {
            is SignatureResult.BadSignature -> {
                log.error("Plugin '{}' signature verification failed: {}", pluginId, sigResult.reason)
                stopPlugin(pluginWrapper)
                return
            }

            is SignatureResult.Unsigned -> {
                log.warn("Plugin '{}' is unsigned: {}", pluginId, sigResult.reason)
            }

            is SignatureResult.Verified -> {
                log.info("Plugin '{}' signature verified (publisher: {})", pluginId, sigResult.publisherKey)
            }
        }

        val abiResult = AbiChecker.check(manifest)
        if (abiResult is AbiResult.Incompatible) {
            log.error(
                "Plugin '{}' ABI incompatible (requires {} but host is {}): {}",
                pluginId,
                abiResult.pluginVersion,
                abiResult.hostVersion,
                abiResult.hint,
            )
            stopPlugin(pluginWrapper)
            return
        }

        val hebePlugin =
            try {
                pluginWrapper.plugin as? HebePlugin
                    ?: run {
                        log.error("Plugin '{}' is not a HebePlugin", pluginId)
                        stopPlugin(pluginWrapper)
                        return
                    }
            } catch (e: Exception) {
                log.error("Plugin '{}' failed to cast to HebePlugin: {}", pluginId, e.message)
                stopPlugin(pluginWrapper)
                return
            }

        val pluginHost = hostFactory.create(pluginId, manifest)
        try {
            hebePlugin.init(pluginHost)
            val tools = hebePlugin.tools(pluginHost)
            val namespacedTools = tools.map { tool -> NamespacedTool(tool, pluginId) }
            for (nsTool in namespacedTools) {
                val fullName = "$pluginId:${nsTool.wrappedTool.spec.name}"
                toolRegistry.register(fullName, NamespacedToolWrapper(nsTool, redactor))
            }
            pluginStore.register(pluginId, namespacedTools)
            log.info("Plugin '{}' started with {} tool(s)", pluginId, namespacedTools.size)
            observer.span(
                "plugin.start",
                mapOf("plugin.id" to pluginId, "plugin.version" to pluginWrapper.descriptor.version),
            ).use { span -> }
        } catch (e: Exception) {
            log.error("Plugin '{}' init failed: {}", pluginId, e.message, e)
            observer.span("plugin_init_error", mapOf("pluginId" to pluginId)).use { span ->
                span.setAttribute("error", true)
                span.recordError(e)
            }
            try {
                hebePlugin.teardown()
            } catch (_: Exception) {
            }
            stopPlugin(pluginWrapper)
        }
    }

    open fun beforeStop(pluginWrapper: PluginWrapper) {
        val pluginId = pluginWrapper.pluginId
        log.info("Stopping plugin '{}'", pluginId)

        val namespacedTools = pluginStore.unregister(pluginId)
        if (namespacedTools != null) {
            for (nsTool in namespacedTools.tools) {
                val fullName = "$pluginId:${nsTool.wrappedTool.spec.name}"
                toolRegistry.unregister(fullName)
            }
        }

        val hebePlugin = pluginWrapper.plugin as? HebePlugin
        if (hebePlugin != null) {
            try {
                hebePlugin.teardown()
            } catch (e: Exception) {
                log.warn("Plugin '{}' teardown error: {}", pluginId, e.message)
            }
        }

        log.info("Plugin '{}' stopped", pluginId)
    }

    private fun loadManifest(pluginWrapper: PluginWrapper): ManifestResult<PluginManifest> {
        val pluginPath = pluginWrapper.pluginPath
        return try {
            ManifestParser.parseFromJar(pluginPath, "plugin.toml")
        } catch (e: Exception) {
            ManifestResult.Error(
                listOf(
                    ManifestError(
                        message = "Failed to read plugin.toml from JAR: ${e.message}",
                        source = pluginPath.toString(),
                    ),
                ),
            )
        }
    }

    private fun computeArchiveHash(pluginWrapper: PluginWrapper): ByteArray =
        try {
            val archivePath = pluginWrapper.pluginPath
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.digest(archivePath.toFile().readBytes())
        } catch (e: Exception) {
            log.warn("Could not compute archive hash for plugin '{}': {}", pluginWrapper.pluginId, e.message)
            ByteArray(32)
        }

    private fun stopPlugin(pluginWrapper: PluginWrapper) {
        try {
            pluginManager.stopPlugin(pluginWrapper.pluginId)
        } catch (e: Exception) {
            log.warn("Could not stop plugin '{}': {}", pluginWrapper.pluginId, e.message)
        }
    }

    interface ToolRegistryWrapper {
        fun register(
            name: String,
            tool: Tool,
        )

        fun unregister(name: String)
    }
}

class NamespacedTool(
    val wrappedTool: Tool,
    val pluginId: String,
) : Tool by wrappedTool {
    override val spec: ToolSpec = wrappedTool.spec.copy(name = "$pluginId:${wrappedTool.spec.name}")
    val fullName: String = "$pluginId:${wrappedTool.spec.name}"
}

class NamespacedToolWrapper(
    private val delegate: NamespacedTool,
    private val redactor: ArgsRedactor,
) : Tool by delegate.wrappedTool {
    override suspend fun invoke(
        args: kotlinx.serialization.json.JsonObject,
        ctx: org.tatrman.kantheon.hebe.api.ToolContext,
    ): org.tatrman.kantheon.hebe.api.ToolResult = delegate.wrappedTool.invoke(args, ctx)
}
