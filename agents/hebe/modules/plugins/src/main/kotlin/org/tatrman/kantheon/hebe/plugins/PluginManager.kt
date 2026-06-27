@file:Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements", "UnusedPrivateProperty")

package org.tatrman.kantheon.hebe.plugins

import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.plugin.api.HebePlugin
import org.tatrman.kantheon.hebe.plugin.api.PluginManifest
import org.tatrman.kantheon.hebe.plugins.host.HostFactory
import org.tatrman.kantheon.hebe.plugins.manifest.ManifestParser
import org.tatrman.kantheon.hebe.plugins.manifest.ManifestResult
import java.nio.file.Path
import org.slf4j.LoggerFactory

class PluginManager(
    private val pluginDir: Path,
    private val hostFactory: HostFactory,
    private val secretResolver: (String) -> String?,
    private val lifecycle: Lifecycle,
) {
    private val log = LoggerFactory.getLogger(PluginManager::class.java)
    private val pf4j: HebePluginManager = HebePluginManager(pluginDir, lifecycle)

    fun start() {
        log.info("Loading plugins from {}", pluginDir)
        pf4j.loadPlugins()
        pf4j.startPlugins()
        log.info("Loaded {} plugin(s)", pf4j.plugins.size)
    }

    fun tools(): List<Tool> {
        val result = mutableListOf<Tool>()
        for (pluginWrapper in pf4j.plugins) {
            val hebePlugin =
                pluginWrapper.plugin as? HebePlugin
                    ?: continue
            val manifest = loadManifestForPlugin(pluginWrapper)
            if (manifest == null) {
                log.warn("Could not load manifest for plugin '{}', skipping", pluginWrapper.pluginId)
                continue
            }
            val pluginHost = hostFactory.create(pluginWrapper.pluginId, manifest)
            hebePlugin.init(pluginHost)
            val rawTools = hebePlugin.tools(pluginHost)
            val namespacedTools = rawTools.map { NamespacedTool(it, pluginWrapper.pluginId) }
            result.addAll(namespacedTools)
        }
        return result
    }

    fun stop() {
        log.info("Stopping plugins")
        pf4j.stopPlugins()
        pf4j.unloadPlugins()
    }

    private fun loadManifestForPlugin(wrapper: org.pf4j.PluginWrapper): PluginManifest? {
        val pluginPath = wrapper.pluginPath
        return when (val result = ManifestParser.parseFromJar(pluginPath, "plugin.toml")) {
            is ManifestResult.Ok -> result.value
            is ManifestResult.Error -> {
                log.warn("Failed to parse plugin manifest for '{}': {}", wrapper.pluginId, result.errors)
                null
            }
        }
    }
}
