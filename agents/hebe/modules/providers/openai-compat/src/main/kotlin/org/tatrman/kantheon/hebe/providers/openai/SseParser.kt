@file:Suppress(
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
)

package org.tatrman.kantheon.hebe.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object SseParser {
    data class AccumulatedToolCall(
        val index: Int,
        val id: StringBuilder = StringBuilder(),
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )

    fun parseChunk(
        line: String,
        accumulated: MutableMap<Int, AccumulatedToolCall>,
    ): List<ParsedChunkPart> {
        if (!line.startsWith("data: ")) return emptyList()
        val data = line.removePrefix("data: ").trim()
        if (data == "[DONE]") return listOf(ParsedChunkPart.Done)

        val jsonElement =
            try {
                Json.parseToJsonElement(data)
            } catch (_: Exception) {
                return emptyList()
            }

        if (jsonElement !is JsonObject) return emptyList()
        val parts = mutableListOf<ParsedChunkPart>()
        val delta = jsonElement["delta"] as? JsonObject ?: return emptyList()

        val content = (delta["content"] as? JsonPrimitive)?.content
        if (content != null) {
            parts.add(ParsedChunkPart.TextDelta(content))
        }

        val toolCalls = delta["tool_calls"] as? JsonArray
        if (toolCalls != null) {
            for (tc in toolCalls) {
                val tcObj = tc as? JsonObject ?: continue
                val index = (tcObj["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue

                val acc = accumulated.getOrPut(index) { AccumulatedToolCall(index) }

                (tcObj["id"] as? JsonPrimitive)?.content?.let {
                    if (it.isNotEmpty()) acc.id.append(it)
                }
                val fn = tcObj["function"] as? JsonObject
                if (fn != null) {
                    (fn["name"] as? JsonPrimitive)?.content?.let {
                        if (it.isNotEmpty()) acc.name.append(it)
                    }
                    (fn["arguments"] as? JsonPrimitive)?.content?.let {
                        if (it.isNotEmpty()) acc.arguments.append(it)
                    }
                }

                if (acc.id.isNotEmpty() && acc.name.isNotEmpty() && isCompleteJson(acc.arguments.toString())) {
                    parts.add(
                        ParsedChunkPart.ToolCallReady(
                            id = acc.id.toString(),
                            name = acc.name.toString(),
                            arguments = acc.arguments.toString(),
                        ),
                    )
                    accumulated.remove(index)
                }
            }
        }

        return parts
    }

    private fun isCompleteJson(s: String): Boolean {
        if (s.isEmpty()) return false
        return try {
            Json.parseToJsonElement(s)
            true
        } catch (_: Exception) {
            false
        }
    }

    sealed class ParsedChunkPart {
        data class TextDelta(
            val text: String,
        ) : ParsedChunkPart()

        data class ToolCallReady(
            val id: String,
            val name: String,
            val arguments: String,
        ) : ParsedChunkPart()

        data object Done : ParsedChunkPart()
    }
}
