package org.tatrman.kantheon.hebe.plugin.api

import org.pf4j.Plugin
import org.pf4j.PluginWrapper

abstract class HebePlugin(
    wrapper: PluginWrapper,
) : Plugin(wrapper) {
    open fun tools(host: PluginHost): List<org.tatrman.kantheon.hebe.api.Tool> = emptyList()

    open fun init(host: PluginHost) {}

    open fun teardown() {}
}

@JvmInline
value class PluginId(
    val value: String,
)

@JvmInline
value class PluginVersion(
    val value: String,
)

enum class Capability {
    Tool,
    Skill,
}

sealed interface Permission {
    data object HttpClient : Permission

    data object EnvRead : Permission

    data class Secret(
        val name: String,
    ) : Permission
}

class PluginCapabilityException(
    message: String,
) : RuntimeException(message)

@JvmInline
value class SecretHandle(
    val name: String,
)
