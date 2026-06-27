@file:Suppress("TooGenericExceptionCaught", "NewLineAtEndOfFile", "TooGenericExceptionThrown")

package org.tatrman.kantheon.hebe.config

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileSettingsStore(
    private val settingsPath: Path,
) : SettingsStore {
    private val mutex = Mutex()
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private suspend fun readSettings(): SettingsData =
        mutex.withLock {
            if (!Files.exists(settingsPath)) {
                return@withLock SettingsData(plugins = emptyMap())
            }
            try {
                val content = Files.readString(settingsPath)
                json.decodeFromString<SettingsData>(content)
            } catch (e: Exception) {
                if (Files.exists(settingsPath)) {
                    throw RuntimeException("Failed to read settings from $settingsPath", e)
                }
                SettingsData(plugins = emptyMap())
            }
        }

    private suspend fun writeSettings(data: SettingsData) =
        mutex.withLock {
            Files.createDirectories(settingsPath.parent)
            Files.writeString(settingsPath, json.encodeToString(data))
        }

    override suspend fun getInstalledPlugins(): List<PluginInstallRecord> {
        val settings = readSettings()
        return settings.plugins.values
            .toList()
            .sortedBy { it.installedAt }
    }

    override suspend fun setInstalledPlugin(
        name: String,
        version: String,
        source: String?,
    ) {
        val settings = readSettings()
        val record =
            PluginInstallRecord(
                name = name,
                version = version,
                installedAt = System.currentTimeMillis(),
                source = source,
            )
        writeSettings(settings.copy(plugins = settings.plugins + (name to record)))
    }

    override suspend fun removeInstalledPlugin(name: String): Boolean {
        val settings = readSettings()
        if (name !in settings.plugins) {
            return false
        }
        writeSettings(settings.copy(plugins = settings.plugins - name))
        return true
    }

    override suspend fun getInstalledPlugin(name: String): PluginInstallRecord? = readSettings().plugins[name]
}

@kotlinx.serialization.Serializable
private data class SettingsData(
    val plugins: Map<String, PluginInstallRecord> = emptyMap(),
)

fun defaultSettingsStore(): SettingsStore {
    val settingsPath =
        Path.of(
            System.getProperty("user.home"),
            ".hebe",
            "settings.json",
        )
    return FileSettingsStore(settingsPath)
}
