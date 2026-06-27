@file:Suppress("NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins

import java.util.concurrent.ConcurrentHashMap

data class PluginRegistration(
    val pluginId: String,
    val tools: List<NamespacedTool>,
)

class PluginRegistrationStore {
    private val store = ConcurrentHashMap<String, PluginRegistration>()

    fun register(
        pluginId: String,
        tools: List<NamespacedTool>,
    ) {
        store[pluginId] = PluginRegistration(pluginId, tools)
    }

    fun unregister(pluginId: String): PluginRegistration? = store.remove(pluginId)

    fun get(pluginId: String): PluginRegistration? = store[pluginId]

    fun all(): Collection<PluginRegistration> = store.values
}
