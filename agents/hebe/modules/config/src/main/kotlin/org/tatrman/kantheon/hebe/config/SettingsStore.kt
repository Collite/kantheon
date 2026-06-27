@file:Suppress("NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.config

import kotlinx.serialization.Serializable

@Serializable
data class PluginInstallRecord(
    val name: String,
    val version: String,
    val installedAt: Long = System.currentTimeMillis(),
    val source: String? = null,
)

interface SettingsStore {
    suspend fun getInstalledPlugins(): List<PluginInstallRecord>

    suspend fun setInstalledPlugin(
        name: String,
        version: String,
        source: String? = null,
    )

    suspend fun removeInstalledPlugin(name: String): Boolean

    suspend fun getInstalledPlugin(name: String): PluginInstallRecord?
}
