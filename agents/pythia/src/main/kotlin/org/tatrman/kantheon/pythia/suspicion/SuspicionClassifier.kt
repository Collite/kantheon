package org.tatrman.kantheon.pythia.suspicion

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.tatrman.kantheon.pythia.plan.PythiaModels

/** What a result was expected to look like (used to flag anomalies). */
data class Expectation(
    val expectRows: Boolean = true,
    val expectedRowCount: Long? = null,
    val expectedColumns: List<String> = emptyList(),
    val loadBearing: Boolean = false,
)

/** A suspicion verdict over a step result. */
data class SuspicionVerdict(
    val suspicious: Boolean,
    val reasons: List<String>,
)

/**
 * Flags dodgy step results (Stage 3.1 T2/T3). A **rules checklist** runs first:
 * empty-where-expected, 10× / 0.1× row-count anomalies, high NULL-rate, schema
 * mismatch, and security flags forwarded from query-mcp. When the rules find
 * nothing but the result feeds a load-bearing hypothesis, a gated **CHEAP-tier**
 * fuzzy check can weigh in (only when an executor is wired).
 */
class SuspicionClassifier {
    fun classify(
        rows: JsonArray,
        columns: List<String>,
        securityFlags: List<String>,
        expectation: Expectation,
    ): SuspicionVerdict {
        val reasons = mutableListOf<String>()
        if (expectation.expectRows && rows.isEmpty()) reasons += "empty result where rows were expected"
        expectation.expectedRowCount?.let { expected ->
            if (expected > 0) {
                if (rows.size > expected * 10) reasons += "row count ${rows.size} is >10× the expected $expected"
                if (rows.size < expected * 0.1) reasons += "row count ${rows.size} is <0.1× the expected $expected"
            }
        }
        highNullColumns(rows).forEach { reasons += "high NULL rate in column '$it'" }
        if (expectation.expectedColumns.isNotEmpty() && columns.toSet() != expectation.expectedColumns.toSet()) {
            reasons += "schema mismatch: got $columns, expected ${expectation.expectedColumns}"
        }
        if (securityFlags.isNotEmpty()) reasons += "security flags: ${securityFlags.joinToString(", ")}"
        return SuspicionVerdict(reasons.isNotEmpty(), reasons)
    }

    /**
     * Gated CHEAP-tier fuzzy check: only when the rules found nothing, the result is
     * load-bearing, and an [executor] is wired. Returns a fuzzy verdict or null (no call).
     */
    suspend fun fuzzyCheck(
        rows: JsonArray,
        hypothesisStatement: String,
        rulesVerdict: SuspicionVerdict,
        expectation: Expectation,
        executor: PromptExecutor?,
    ): SuspicionVerdict? {
        if (rulesVerdict.suspicious || !expectation.loadBearing || executor == null) return null
        val p =
            prompt("pythia-suspicion") {
                system(
                    "Judge if this result distribution is implausible for the hypothesis. Reply 'SUSPICIOUS: <reason>' or 'OK'.",
                )
                user("Hypothesis: $hypothesisStatement\nData: $rows")
            }
        val reply =
            executor
                .execute(p, PythiaModels.Cheap, emptyList())
                .filterIsInstance<Message.Assistant>()
                .joinToString(" ") { it.content }
        return if (reply.trim().uppercase().startsWith("SUSPICIOUS")) {
            SuspicionVerdict(true, listOf("fuzzy: ${reply.substringAfter(":").trim()}"))
        } else {
            SuspicionVerdict(false, emptyList())
        }
    }

    private fun highNullColumns(rows: JsonArray): List<String> {
        if (rows.isEmpty()) return emptyList()
        val objs = rows.mapNotNull { it as? JsonObject }
        if (objs.isEmpty()) return emptyList()
        val cols = objs.flatMap { it.keys }.toSet()
        return cols.filter { col ->
            val present = objs.count { it.containsKey(col) }
            if (present == 0) {
                false
            } else {
                objs.count { it[col] == null || it[col] is JsonNull }.toDouble() / present > 0.5
            }
        }
    }
}
