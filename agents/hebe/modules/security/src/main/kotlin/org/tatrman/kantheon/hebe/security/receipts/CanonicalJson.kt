package org.tatrman.kantheon.hebe.security.receipts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object CanonicalJson {
    private val json =
        Json {
            explicitNulls = false
        }

    fun serialize(obj: JsonObject): String = json.encodeToString(JsonObject.serializer(), obj)

    fun serializeCanonical(entries: List<Pair<String, Any?>>): String {
        val obj = buildCanonicalObject(entries)
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun buildCanonicalObject(entries: List<Pair<String, Any?>>): JsonObject {
        val sortedEntries = entries.sortedBy { it.first }
        return buildJsonObject {
            for ((key, value) in sortedEntries) {
                put(key, valueToJsonElement(value))
            }
        }
    }

    private fun valueToJsonElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> ->
                buildJsonObject {
                    value.entries.sortedBy { it.key.toString() }.forEach { (k, v) ->
                        put(k.toString(), valueToJsonElement(v))
                    }
                }
            else -> JsonPrimitive(value.toString())
        }
}
