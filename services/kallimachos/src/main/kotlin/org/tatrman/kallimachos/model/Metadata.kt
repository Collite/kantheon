package org.tatrman.kallimachos.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.mapSerialDescriptor
import kotlinx.serialization.descriptors.serialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Corpus metadata value model. Ported from doc-store `com.docstore.model.Metadata`
 * (framework-agnostic; package change only). Mirrors the proto `MetadataValue`
 * (`oneof single | list`, contracts §1); jsonb-bound at Stage 1.2. The nested
 * JSON form is `{"value":"A"}` / `{"values":["B","C"]}`.
 */
@Serializable
sealed interface MetadataValue

@Serializable
data class MetadataSingle(
    val value: String,
) : MetadataValue

@Serializable
data class MetadataList(
    val values: List<String>,
) : MetadataValue

/**
 * Parse a JSON string representing metadata into a Map<String, MetadataValue>.
 * Values can be a string or an array of strings. Other types are stringified.
 */
fun parseMetadataJson(jsonText: String): Map<String, MetadataValue> {
    val root = Json.parseToJsonElement(jsonText)
    if (root !is JsonObject) return emptyMap()
    val out = mutableMapOf<String, MetadataValue>()
    for ((k, v) in root) {
        out[k] =
            when (v) {
                is JsonPrimitive -> MetadataSingle(v.content)
                is JsonArray -> MetadataList(v.mapNotNull { (it as? JsonPrimitive)?.content })
                else -> MetadataSingle(v.toString())
            }
    }
    return out
}

/**
 * Custom serializer for a single MetadataValue supporting the nested forms
 * `{"value":"A"}` and `{"values":["B","C"]}`. Also leniently accepts primitives
 * and arrays.
 */
object MetadataValueSerializer : KSerializer<MetadataValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MetadataValue")

    override fun deserialize(decoder: Decoder): MetadataValue {
        val jd = decoder as? JsonDecoder ?: error("MetadataValueSerializer requires Json")
        val elem = jd.decodeJsonElement()
        return decodeElement(elem)
    }

    fun decodeElement(elem: JsonElement): MetadataValue =
        when (elem) {
            is JsonObject -> {
                val v = elem["value"]
                val vs = elem["values"]
                when {
                    v is JsonPrimitive -> MetadataSingle(v.content)
                    vs is JsonArray -> MetadataList(vs.mapNotNull { (it as? JsonPrimitive)?.content })
                    elem.size == 1 && elem.values.firstOrNull() is JsonPrimitive ->
                        MetadataSingle((elem.values.first() as JsonPrimitive).content)
                    elem.size == 1 && elem.values.firstOrNull() is JsonArray ->
                        MetadataList((elem.values.first() as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content })
                    else -> MetadataSingle(elem.toString())
                }
            }
            is JsonPrimitive -> MetadataSingle(elem.content)
            is JsonArray -> MetadataList(elem.mapNotNull { (it as? JsonPrimitive)?.content })
            else -> MetadataSingle(elem.toString())
        }

    override fun serialize(
        encoder: Encoder,
        value: MetadataValue,
    ) {
        val je = encoder as? JsonEncoder ?: error("MetadataValueSerializer requires Json")
        je.encodeJsonElement(encodeValue(value))
    }

    fun encodeValue(value: MetadataValue): JsonObject =
        when (value) {
            is MetadataSingle -> buildJsonObject { put("value", JsonPrimitive(value.value)) }
            is MetadataList ->
                buildJsonObject {
                    put("values", JsonArray(value.values.map { JsonPrimitive(it) }))
                }
        }
}

/**
 * Serializer for the metadata map structure required by the API:
 * `{ "one": {"value": "A"}, "two": {"values": ["B", "C"]} }`.
 */
object MetadataMapSerializer : KSerializer<Map<String, MetadataValue>> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        mapSerialDescriptor(
            serialDescriptor<String>(),
            serialDescriptor<MetadataValue>(),
        )

    override fun deserialize(decoder: Decoder): Map<String, MetadataValue> {
        val jd = decoder as? JsonDecoder ?: error("MetadataMapSerializer requires Json")
        val elem = jd.decodeJsonElement()
        if (elem !is JsonObject) return emptyMap()
        val out = linkedMapOf<String, MetadataValue>()
        for ((k, v) in elem) {
            out[k] = MetadataValueSerializer.decodeElement(v)
        }
        return out
    }

    override fun serialize(
        encoder: Encoder,
        value: Map<String, MetadataValue>,
    ) {
        val je = encoder as? JsonEncoder ?: error("MetadataMapSerializer requires Json")
        val obj =
            buildJsonObject {
                value.forEach { (k, v) ->
                    put(k, MetadataValueSerializer.encodeValue(v))
                }
            }
        je.encodeJsonElement(obj)
    }
}
