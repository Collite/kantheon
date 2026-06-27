package org.tatrman.kallimachos.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * MetadataValue model + serializers — round-trips single + list values through
 * the nested API form (`{"value":…}` / `{"values":[…]}`) and the lenient JSON
 * parser. Parity with doc-store `MetadataSpec` / `Metadata.kt`.
 */
class MetadataSpec :
    StringSpec({
        "single + list round-trip through the map serializer" {
            val original: Map<String, MetadataValue> =
                linkedMapOf(
                    "author" to MetadataSingle("Bora"),
                    "tags" to MetadataList(listOf("erp", "finance")),
                )
            val json = Json.encodeToString(MetadataMapSerializer, original)
            val back = Json.decodeFromString(MetadataMapSerializer, json)
            back shouldBe original
        }

        "the nested API form serialises as {value} / {values}" {
            val json = Json.encodeToString(MetadataMapSerializer, mapOf("a" to MetadataSingle("X")))
            json shouldBe """{"a":{"value":"X"}}"""
            val listJson = Json.encodeToString(MetadataMapSerializer, mapOf("b" to MetadataList(listOf("B", "C"))))
            listJson shouldBe """{"b":{"values":["B","C"]}}"""
        }

        "parseMetadataJson leniently accepts primitives and arrays" {
            val parsed = parseMetadataJson("""{"author":"Bora","tags":["a","b"]}""")
            parsed["author"] shouldBe MetadataSingle("Bora")
            parsed["tags"] shouldBe MetadataList(listOf("a", "b"))
        }

        "parseMetadataJson returns empty for a non-object root" {
            parseMetadataJson("""["not","an","object"]""") shouldBe emptyMap()
        }
    })
