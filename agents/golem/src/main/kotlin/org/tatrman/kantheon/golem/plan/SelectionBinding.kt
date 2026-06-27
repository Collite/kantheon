package org.tatrman.kantheon.golem.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.v1.MiniPlan

private val selectionJson = Json { ignoreUnknownKeys = true }

/**
 * Fill a composed plan's **unfilled** pattern parameters from a row-detail selection
 * (`_bind_selection_args`, S2.4 §10 Δ4). Mirrors the Python rail: for the primary
 * pattern query, any declared param the plan left unbound is filled from the selected
 * row's column whose name matches case-insensitively. An explicitly-bound arg always
 * wins — selection is binding *context*, never an override. No selection / no model /
 * no pattern node → the plan is returned unchanged.
 */
fun bindSelectionArgs(
    plan: MiniPlan,
    selectionContext: JsonObject,
    model: ModelSnapshot?,
): MiniPlan {
    if (selectionContext.isEmpty() || model == null) return plan
    val selByUpper: Map<String, JsonElement> = selectionContext.entries.associate { (k, v) -> k.uppercase() to v }

    val builder = plan.toBuilder()
    val primaryIdx =
        builder.nodesBuilderList.indexOfFirst { it.hasQuery() && it.query.hasPatternId() }
    if (primaryIdx < 0) return plan
    val nodeB = builder.getNodesBuilder(primaryIdx)
    val pq = model.patternQuery(nodeB.query.patternId) ?: return plan

    val args = parseArgs(nodeB.query.paramsJson).toMutableMap()
    var changed = false
    for (p in pq.parametersList) {
        if (isBound(args[p.name])) continue // explicit arg wins
        val v = selByUpper[p.name.uppercase()] ?: continue
        args[p.name] = v
        changed = true
    }
    if (!changed) return plan
    builder.getNodesBuilder(primaryIdx).queryBuilder.paramsJson = JsonObject(args).toString()
    return builder.build()
}

private fun isBound(el: JsonElement?): Boolean =
    when {
        el == null -> false
        el is JsonPrimitive && el.isString && el.content.isBlank() -> false
        else -> true
    }

private fun parseArgs(rawArgsJson: String): Map<String, JsonElement> {
    if (rawArgsJson.isBlank() || rawArgsJson == "{}") return emptyMap()
    return runCatching { selectionJson.parseToJsonElement(rawArgsJson).jsonObject.toMap() }.getOrElse { emptyMap() }
}
