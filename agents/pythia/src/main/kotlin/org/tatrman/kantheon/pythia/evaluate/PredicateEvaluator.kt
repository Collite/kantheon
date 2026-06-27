package org.tatrman.kantheon.pythia.evaluate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.pythia.v1.Predicate
import kotlin.math.abs
import kotlin.math.sqrt

/** Predicate outcome. NON_APPLICABLE = the result shape doesn't fit (→ LLM fallback in Phase 3, not an error). */
enum class PredicateVerdict { PASS, FAIL, NON_APPLICABLE }

/**
 * Rules-first predicate evaluation (Stage 2.3 T3, no LLM). Each predicate kind is
 * evaluated over a result's JSON rows; a predicate that can't apply (missing column,
 * too few rows) returns NON_APPLICABLE rather than failing.
 */
object PredicateEvaluator {
    private val json = Json { ignoreUnknownKeys = true }

    fun evaluate(
        predicate: Predicate,
        rows: JsonArray,
    ): PredicateVerdict {
        val params =
            runCatching {
                json.parseToJsonElement(predicate.parametersJson.ifBlank { "{}" }).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))
        val threshold = predicate.threshold
        return when (predicate.kind) {
            Predicate.Kind.ROW_COUNT_GT -> bool(rows.size > threshold)
            Predicate.Kind.ROW_COUNT_LT -> bool(rows.size < threshold)
            Predicate.Kind.ROW_COUNT_EQ -> bool(rows.size.toDouble() == threshold)
            Predicate.Kind.METRIC_DELTA_RATIO -> metricDeltaRatio(rows, params, threshold)
            Predicate.Kind.NULL_RATE_LT -> nullRateLt(rows, params, threshold)
            Predicate.Kind.CORRELATION_STRENGTH -> correlationStrength(rows, params, threshold)
            else -> PredicateVerdict.NON_APPLICABLE
        }
    }

    /** (last − first) / |first| of the metric column, compared to the threshold (e.g. −0.05 = "down ≥5%"). */
    private fun metricDeltaRatio(
        rows: JsonArray,
        params: JsonObject,
        threshold: Double,
    ): PredicateVerdict {
        val col = params["column"]?.jsonPrimitive?.content ?: return PredicateVerdict.NON_APPLICABLE
        val series = rows.mapNotNull { num(it as? JsonObject, col) }
        if (series.size < 2) return PredicateVerdict.NON_APPLICABLE
        val first = series.first()
        if (first == 0.0) return PredicateVerdict.NON_APPLICABLE
        val ratio = (series.last() - first) / abs(first)
        // PASS when the observed drop/rise is at least as extreme as the (signed) threshold.
        return bool(if (threshold < 0) ratio <= threshold else ratio >= threshold)
    }

    private fun nullRateLt(
        rows: JsonArray,
        params: JsonObject,
        threshold: Double,
    ): PredicateVerdict {
        val col = params["column"]?.jsonPrimitive?.content ?: return PredicateVerdict.NON_APPLICABLE
        if (rows.isEmpty()) return PredicateVerdict.NON_APPLICABLE
        val objs = rows.mapNotNull { it as? JsonObject }
        if (objs.none { it.containsKey(col) }) return PredicateVerdict.NON_APPLICABLE
        val nulls = objs.count { (it[col] == null) || (it[col] is JsonNull) }
        return bool(nulls.toDouble() / objs.size < threshold)
    }

    private fun correlationStrength(
        rows: JsonArray,
        params: JsonObject,
        threshold: Double,
    ): PredicateVerdict {
        val xc = params["x"]?.jsonPrimitive?.content ?: return PredicateVerdict.NON_APPLICABLE
        val yc = params["y"]?.jsonPrimitive?.content ?: return PredicateVerdict.NON_APPLICABLE
        val pairs =
            rows.mapNotNull { r ->
                (r as? JsonObject)?.let { o ->
                    num(o, xc)?.let { x ->
                        num(o, yc)?.let { y ->
                            x to
                                y
                        }
                    }
                }
            }
        if (pairs.size < 2) return PredicateVerdict.NON_APPLICABLE
        val r = pearson(pairs) ?: return PredicateVerdict.NON_APPLICABLE
        return bool(abs(r) >= threshold)
    }

    private fun pearson(pairs: List<Pair<Double, Double>>): Double? {
        val n = pairs.size
        val mx = pairs.sumOf { it.first } / n
        val my = pairs.sumOf { it.second } / n
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for ((x, y) in pairs) {
            sxy += (x - mx) * (y - my)
            sxx += (x - mx) * (x - mx)
            syy += (y - my) * (y - my)
        }
        if (sxx == 0.0 || syy == 0.0) return null
        return sxy / sqrt(sxx * syy)
    }

    private fun num(
        obj: JsonObject?,
        col: String,
    ): Double? =
        obj
            ?.get(col)
            ?.jsonPrimitive
            ?.content
            ?.toDoubleOrNull()

    private fun bool(value: Boolean): PredicateVerdict = if (value) PredicateVerdict.PASS else PredicateVerdict.FAIL
}
