package org.tatrman.kantheon.capabilities.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.capabilities.registry.RegistryQueryService
import org.tatrman.kantheon.capabilities.v1.CapabilityFilter
import org.tatrman.kantheon.capabilities.v1.IntentKind

internal object JsonAdapters {
    fun searchParamsFromJson(req: JsonObject): RegistryQueryService.SearchParams =
        RegistryQueryService.SearchParams(
            intentKinds = req.stringList("intentKinds").map(IntentKind::valueOf),
            entityTypes = req.stringList("entityTypes"),
            capabilityTags = req.stringList("capabilityTags"),
            filter = (req["filter"] as? JsonObject)?.toCapabilityFilter(),
        )

    fun listParamsFromJson(req: JsonObject): RegistryQueryService.ListParams =
        RegistryQueryService.ListParams(
            category = (req["category"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            filter = (req["filter"] as? JsonObject)?.toCapabilityFilter(),
        )

    fun listAgentsFilterFromJson(req: JsonObject): CapabilityFilter? =
        (req["filter"] as? JsonObject)?.toCapabilityFilter()

    fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    fun JsonObject.toCapabilityFilter(): CapabilityFilter {
        val b = CapabilityFilter.newBuilder()
        bool("includeTools")?.let { b.includeTools = it }
        bool("includeAgents")?.let { b.includeAgents = it }
        bool("includePruned")?.let { b.includePruned = it }
        return b.build()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val node = this[key] as? JsonPrimitive ?: return null
        return node.content.toBooleanStrictOrNull()
    }
}
