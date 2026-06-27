### Kotlin + Ktor Response Serialization
**❌ DON'T use `mapOf` for `call.respond()`** - Kotlin's type erasure causes issues when `mapOf` contains mixed types (e.g., `mapOf("found" to false, "error" to "string")`). The compiler erases the generic `Map<String, T>` to `Map<String, Any>`.

**✅ ALWAYS use `buildJsonObject` with explicit `JsonPrimitive` values:**
```kotlin
// Wrong - causes serialization issues
call.respond(HttpStatusCode.OK, mapOf("found" to false, "error" to "Entity not found"))

// Correct
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

call.respond(HttpStatusCode.OK, buildJsonObject {
    put("found", JsonPrimitive(false))
    put("error", JsonPrimitive("Entity not found"))
})
```

For nested arrays, use `JsonArray`:
```kotlin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

call.respond(buildJsonObject {
    put("items", JsonArray(someList.map { JsonPrimitive(it) }))
})
```
## Examples

### Serialization Example
This is the example of a multi-type field in a data class.
Please, note that the required behavior is to accept the primitives or arrays WITHOUT specifying the "value" or "values" field.
Preferred implementation would be to use the inner classes in the interface, like this:
```kotlin
@Serializable
sealed interface MetadataValue {
    @Serializable
    data class MetadataSingle(val value: String) : MetadataValue
    @Serializable
    data class MetadataList(val values: List<String>) : MetadataValue
}
```

This is the example interface, classes and custom serializer:
```kotlin
@Serializable
sealed interface MetadataValue

@Serializable
data class MetadataSingle(val value: String) : MetadataValue

@Serializable
data class MetadataList(val values: List<String>) : MetadataValue

/**
 * Parse a JSON string representing metadata into a Map<String, MetadataValue>.
 * Values can be a string or an array of strings. Other types will be stringified.
 */
fun parseMetadataJson(jsonText: String): Map<String, MetadataValue> {
    val root = Json.parseToJsonElement(jsonText)
    if (root !is kotlinx.serialization.json.JsonObject) return emptyMap()
    val out = mutableMapOf<String, MetadataValue>()
    for ((k, v) in root) {
        out[k] = when (v) {
            is JsonPrimitive -> MetadataSingle(v.content)
            is JsonArray -> MetadataList(v.mapNotNull { (it as? JsonPrimitive)?.content })
            else -> MetadataSingle(v.toString())
        }
    }
    return out
}

/**
 * Custom serializer for a single MetadataValue that supports the nested forms:
 * {"value":"A"} and {"values":["B","C"]}.
 * Also leniently accepts primitives and arrays.
 */
object MetadataValueSerializer : KSerializer<MetadataValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MetadataValue")

    override fun deserialize(decoder: Decoder): MetadataValue {
        val jd = decoder as? JsonDecoder ?: error("MetadataValueSerializer requires Json")
        val elem = jd.decodeJsonElement()
        return when (elem) {
            is JsonObject -> {
                val v = elem["value"]
                val vs = elem["values"]
                when {
                    v is JsonPrimitive -> MetadataSingle(v.content)
                    vs is JsonArray -> MetadataList(vs.mapNotNull { (it as? JsonPrimitive)?.content })
                    // Fallbacks
                    elem.size == 1 && elem.values.firstOrNull() is JsonPrimitive ->
                        MetadataSingle((elem.values.first() as JsonPrimitive).content)
                    elem.size == 1 && elem.values.firstOrNull() is JsonArray ->
                        MetadataList(((elem.values.first() as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content }))
                    else -> MetadataSingle(elem.toString())
                }
            }
            is JsonPrimitive -> MetadataSingle(elem.content)
            is JsonArray -> MetadataList(elem.mapNotNull { (it as? JsonPrimitive)?.content })
            else -> MetadataSingle(elem.toString())
        }
    }

    override fun serialize(encoder: Encoder, value: MetadataValue) {
        val je = encoder as? JsonEncoder ?: error("MetadataValueSerializer requires Json")
        val obj = when (value) {
            is MetadataSingle -> buildJsonObject { put("value", JsonPrimitive(value.value)) }
            is MetadataList -> buildJsonObject {
                put("values", JsonArray(value.values.map { JsonPrimitive(it) }))
            }
        }
        je.encodeJsonElement(obj)
    }
}
```
