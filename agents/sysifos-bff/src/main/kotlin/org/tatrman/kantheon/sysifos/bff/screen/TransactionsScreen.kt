package org.tatrman.kantheon.sysifos.bff.screen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Midas `TransactionSource` value for a derived cash leg (contracts §1.1.A). */
private const val SOURCE_DERIVATION = "TX_SRC_DERIVATION"

private val json = Json { ignoreUnknownKeys = true }

/**
 * Assemble the Transactions screen (contracts §3.4) from three Midas-core
 * responses — the portfolios list, the assets list, and a transactions page.
 * Security legs stay top-level; **derived cash legs** (`source = TX_SRC_DERIVATION`)
 * are pulled out and nested under their security leg by shared `correlationId`,
 * so the FE renders them as dimmed sub-rows without a second round-trip. Cash legs
 * with no matching security leg in the page (paging edge) are kept top-level so
 * nothing silently disappears.
 *
 * Pure over the raw bodies — the route just forwards three calls and passes the
 * bodies here. Unknown keys are tolerated; the proto-JSON shape is otherwise
 * preserved verbatim.
 */
fun assembleTransactionsScreen(
    transactionsBody: String,
    assetsBody: String,
    portfoliosBody: String,
): String {
    val txRoot = parseObject(transactionsBody)
    val transactions = (txRoot["transactions"] as? JsonArray) ?: JsonArray(emptyList())

    val cashByCorrelation = mutableMapOf<String, MutableList<JsonElement>>()
    val securityLegs = mutableListOf<JsonElement>()
    val orphanCashLegs = mutableListOf<JsonElement>()

    for (tx in transactions) {
        val obj = tx.jsonObject
        if (obj.stringOrNull("source") == SOURCE_DERIVATION) {
            val corr = obj.stringOrNull("correlationId")
            if (corr != null) {
                cashByCorrelation.getOrPut(corr) { mutableListOf() }.add(tx)
            } else {
                orphanCashLegs.add(tx)
            }
        } else {
            securityLegs.add(tx)
        }
    }

    // How many security legs claim each correlation. A reverse+replace edit emits a
    // reversal leg that reuses the original's correlationId (contracts §2.4), so two
    // security legs can share one id; pairing a cash leg to the right one is then
    // ambiguous from a flat page. Attach only when exactly one security leg owns the
    // correlation; otherwise leave the cash legs top-level rather than mis-nesting.
    val securityLegsByCorrelation =
        securityLegs
            .mapNotNull { it.jsonObject.stringOrNull("correlationId") }
            .groupingBy { it }
            .eachCount()

    val grouped =
        securityLegs.map { leg ->
            val corr = leg.jsonObject.stringOrNull("correlationId")
            val legs =
                if (corr != null && securityLegsByCorrelation[corr] == 1) {
                    cashByCorrelation.remove(corr) ?: emptyList()
                } else {
                    emptyList()
                }
            JsonObject(leg.jsonObject + ("cashLegs" to JsonArray(legs)))
        }
    // Cash legs with no single owning security leg in this page (off-page, or a shared
    // correlation) stay visible at the top level so nothing silently disappears.
    val danglingCash = cashByCorrelation.values.flatten() + orphanCashLegs

    return json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("transactions", JsonArray(grouped + danglingCash))
            put("assets", parseObject(assetsBody)["assets"] ?: JsonArray(emptyList()))
            put("portfolios", parseObject(portfoliosBody)["portfolios"] ?: JsonArray(emptyList()))
            txRoot["pageInfo"]?.let { put("pageInfo", it) }
        },
    )
}

private fun parseObject(body: String): JsonObject =
    runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
