package org.tatrman.kantheon.sysifos.bff.screen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

private val importJson = Json { ignoreUnknownKeys = true }

/**
 * Assemble the Import preview screen (contracts §3.4) from two loader responses —
 * the `LoaderRun` and its `LoaderPreview` (rows already carry per-row `decision` +
 * `note`, i.e. the diff vs existing). Folds them into one `{ loaderRun, rows,
 * summary }` payload so the FE renders the grouped preview without a second
 * round-trip. Pure over the raw bodies.
 */
fun assembleImportScreen(
    runBody: String,
    previewBody: String,
): String {
    val run = parseObj(runBody)
    val preview = parseObj(previewBody)
    return importJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("loaderRun", run)
            put("rows", preview["rows"] ?: JsonArray(emptyList()))
            preview["summary"]?.let { put("summary", it) }
        },
    )
}

private fun parseObj(body: String): JsonObject =
    runCatching { importJson.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }
