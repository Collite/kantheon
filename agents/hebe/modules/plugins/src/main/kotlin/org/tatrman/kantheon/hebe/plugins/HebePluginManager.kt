package org.tatrman.kantheon.hebe.plugins

import java.nio.file.Path
import org.pf4j.DefaultPluginManager
import org.pf4j.JarPluginLoader
import org.pf4j.PluginClassLoader
import org.pf4j.PluginDescriptor
import org.pf4j.PluginState
import org.pf4j.PluginStateEvent
import org.pf4j.PluginStateListener
import org.slf4j.LoggerFactory

class HebePluginManager(
    pluginDir: Path,
    private val lifecycle: Lifecycle,
) : DefaultPluginManager(pluginDir) {
    private val log = LoggerFactory.getLogger(HebePluginManager::class.java)

    init {
        log.info("Initialized HebePluginManager with plugin dir: {}", pluginDir)
        addPluginStateListener(
            object : PluginStateListener {
                override fun pluginStateChanged(event: PluginStateEvent) {
                    when (event.pluginState) {
                        PluginState.STARTED -> lifecycle.afterStart(event.plugin)
                        PluginState.STOPPED -> lifecycle.beforeStop(event.plugin)
                        else -> {}
                    }
                }
            },
        )
    }

    override fun createPluginLoader(): org.pf4j.PluginLoader = HebeJarPluginLoader(this)

    override fun deletePlugin(pluginId: String): Boolean {
        val result = super.deletePlugin(pluginId)
        if (result) {
            log.info("Deleted plugin {}", pluginId)
        } else {
            log.warn("Failed to delete plugin {}", pluginId)
        }
        return result
    }

    override fun unloadPlugin(pluginId: String): Boolean {
        log.debug("Unloading plugin {}", pluginId)
        return super.unloadPlugin(pluginId)
    }
}

class HebeJarPluginLoader(
    private val pm: org.pf4j.PluginManager,
) : JarPluginLoader(pm) {
    override fun createPluginClassLoader(
        pluginPath: Path,
        pluginDescriptor: PluginDescriptor,
    ): PluginClassLoader = HebePluginClassLoader(pm, pluginDescriptor, this::class.java.classLoader)
}

class HebePluginClassLoader(
    pm: org.pf4j.PluginManager,
    descriptor: PluginDescriptor,
    parent: ClassLoader,
) : PluginClassLoader(pm, descriptor, parent, false) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (name.startsWith("org.tatrman.kantheon.hebe.") &&
            !name.startsWith("org.tatrman.kantheon.hebe.api.") &&
            !name.startsWith("org.tatrman.kantheon.hebe.plugin.api.")
        ) {
            throw ClassNotFoundException("Plugin cannot access host-private class: $name")
        }
        if (name.startsWith("org.tatrman.kantheon.hebe.api.") || name.startsWith("org.tatrman.kantheon.hebe.plugin.api.")) {
            return parent.loadClass(name)
        }
        return super.loadClass(name, resolve)
    }
}
