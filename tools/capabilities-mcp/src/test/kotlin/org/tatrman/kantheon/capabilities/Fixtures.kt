package org.tatrman.kantheon.capabilities

import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.capabilities.v1.ToolCapability

fun toolCapability(
    id: String,
    description: String = "",
    category: String = id.substringBefore(":") + ".*",
    version: String = id.substringAfter(':', "v1"),
): ToolCapability =
    ToolCapability
        .newBuilder()
        .setCapabilityId(id)
        .setCategory(category)
        .setVersion(version)
        .setDescription(description)
        .build()

fun agentCapability(
    id: String,
    kind: AgentKind,
    configure: AgentCapability.Builder.() -> Unit = {},
): AgentCapability =
    AgentCapability
        .newBuilder()
        .setAgentKind(kind)
        .setAgentId(id)
        .apply(configure)
        .build()

fun ToolCapability.asCapability(): Capability = Capability.newBuilder().setTool(this).build()

fun AgentCapability.asCapability(): Capability = Capability.newBuilder().setAgent(this).build()
