package org.tatrman.kantheon.envelope.render.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes the LLM's structured reply into a [RenderCall], reproducing new-golem
 * v2's `tool_choice="any"` dispatch + Pydantic-validation error taxonomy
 * (`format_catalog.py` / `nodes.py` @ git 5281954d^).
 *
 * The model is asked to return a single JSON object whose `tool` discriminates
 * the render kind:
 *
 * ```json
 * { "tool": "RenderTable", "text": null, "content": [ ... ], "details": { ... } }
 * ```
 *
 * Failures map onto [FormatToolException.Reason] so [org.tatrman.kantheon.envelope.render.fallback.FormatCatalog]
 * can retry with the reason fed back: missing/blank `tool` → `NO_TOOL_CALL`,
 * an unrecognised name → `UNKNOWN_TOOL`, malformed args → `SCHEMA_INVALID`.
 */
object RenderCallCodec {
    val toolNames = listOf("RenderPlaintext", "RenderMarkdown", "RenderTable", "RenderChart")

    private val json = Json { ignoreUnknownKeys = true }

    /** @throws FormatToolException on any non-decodable reply. */
    fun parse(raw: String): RenderCall {
        val cleaned =
            raw
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

        val root =
            try {
                json.parseToJsonElement(cleaned) as? JsonObject
                    ?: throw FormatToolException(FormatToolException.Reason.NO_TOOL_CALL, "reply was not a JSON object")
            } catch (e: FormatToolException) {
                throw e
            } catch (e: Exception) {
                throw FormatToolException(
                    FormatToolException.Reason.SCHEMA_INVALID,
                    "reply was not valid JSON: ${e.message}",
                )
            }

        val tool =
            root["tool"].asStringOrNull()?.takeIf { it.isNotBlank() }
                ?: throw FormatToolException(FormatToolException.Reason.NO_TOOL_CALL, "no `tool` field in reply")
        if (tool !in toolNames) {
            throw FormatToolException(FormatToolException.Reason.UNKNOWN_TOOL, "unknown tool '$tool'")
        }

        return try {
            when (tool) {
                "RenderPlaintext" ->
                    RenderCall.Plaintext(text = root.requireString("text"))
                "RenderMarkdown" ->
                    RenderCall.Markdown(
                        text = root.requireString("text"),
                        openInTabDefaultTitle = root["openInTabDefaultTitle"].asStringOrNull(),
                    )
                "RenderTable" ->
                    RenderCall.Table(
                        text = root["text"].asStringOrNull(),
                        content = root["content"].takeUnless { it is JsonNull } ?: missing("content"),
                        details =
                            root["details"]
                                ?.takeUnless { it is JsonNull }
                                ?.let { json.decodeFromJsonElement(TableDetailsInput.serializer(), it) }
                                ?: TableDetailsInput(),
                    )
                else ->
                    RenderCall.Chart(
                        text = root["text"].asStringOrNull(),
                        content = root["content"] as? JsonArray ?: missing("content"),
                        intent =
                            json.decodeFromJsonElement(
                                ChartIntentInput.serializer(),
                                root["intent"]?.takeUnless { it is JsonNull }
                                    ?: root["details"]?.takeUnless { it is JsonNull }
                                    ?: missing("intent"),
                            ),
                    )
            }
        } catch (e: FormatToolException) {
            throw e
        } catch (e: Exception) {
            throw FormatToolException(FormatToolException.Reason.SCHEMA_INVALID, "invalid $tool args: ${e.message}")
        }
    }

    private fun missing(field: String): Nothing =
        throw FormatToolException(FormatToolException.Reason.SCHEMA_INVALID, "missing `$field`")

    private fun JsonObject.requireString(field: String): String =
        this[field].asStringOrNull()
            ?: throw FormatToolException(FormatToolException.Reason.SCHEMA_INVALID, "missing `$field`")

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}
