package org.tatrman.kantheon.golem.format

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.kantheon.themis.v1.Themis.EntityBinding

/** The render kind inference resolved for a turn (Δ5). */
data class InferredKind(
    val kind: FormatKind,
    val chartType: String? = null,
    val seriesField: String? = null,
)

/**
 * Render-kind inference — a faithful port of ai-platform `format/kind_inference.py`.
 *
 * Precedence (highest first):
 *   1. the pattern's `result_kind_hint` (a `chart` hint → bar chart);
 *   2. **amend-on-compare** (gated `chartOnCompare`): an AMEND that adds a *second value*
 *      for an axis the prior turn already had → bar chart keyed on that axis;
 *   3. otherwise a table.
 */
object KindInference {
    private val log = LoggerFactory.getLogger(KindInference::class.java)

    fun infer(
        resultKindHint: String?,
        planSource: PlanSource,
        currentBindings: List<EntityBinding>,
        priorBindings: List<EntityBinding>,
        chartOnCompare: Boolean,
    ): InferredKind {
        // 1. An explicit, *recognized* pattern hint wins (incl. an explicit "table", which
        //    deliberately suppresses amend-on-compare). An UNRECOGNIZED hint is ignored — it falls
        //    through to amend/table inference rather than silently locking the turn to a table.
        if (!resultKindHint.isNullOrBlank()) {
            val explicit = recognizedKind(resultKindHint)
            if (explicit != null) {
                return if (explicit == FormatKind.CHART) {
                    InferredKind(FormatKind.CHART, chartType = "bar")
                } else {
                    InferredKind(explicit)
                }
            }
            log.warn("unrecognized result_kind_hint '{}' — ignoring, falling through to inference", resultKindHint)
        }
        // 2. AMEND that adds a second series for a shared axis.
        if (chartOnCompare && planSource == PlanSource.AMEND) {
            val axis = addedSecondSeriesAxis(currentBindings, priorBindings)
            if (axis != null) return InferredKind(FormatKind.CHART, chartType = "bar", seriesField = axis)
        }
        // 3. Table.
        return InferredKind(FormatKind.TABLE)
    }

    /** The axis (shared with the prior turn) for which the current turn introduced a new value, or null. */
    private fun addedSecondSeriesAxis(
        current: List<EntityBinding>,
        prior: List<EntityBinding>,
    ): String? {
        val cur = axisValues(current)
        val prev = axisValues(prior)
        for ((axis, values) in cur) {
            val priorValues = prev[axis] ?: continue // axis must exist in BOTH turns
            if (values.any { it !in priorValues }) return axis
        }
        return null
    }

    /** axis key → set of values. Axis = universal type name (upper) or domain entity_type_ref. */
    private fun axisValues(bindings: List<EntityBinding>): Map<String, Set<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        for (b in bindings) {
            val (axis, value) =
                when {
                    b.hasUniversal() ->
                        b.universal.entityType.name
                            .uppercase() to
                            b.universal.normalizedValue.ifBlank { b.universal.rawText }
                    b.hasDomain() ->
                        b.domain.entityTypeRef to b.domain.resolvedLabel.ifBlank { b.domain.rawText }
                    else -> continue
                }
            if (axis.isNotBlank() && value.isNotBlank()) out.getOrPut(axis) { LinkedHashSet() }.add(value)
        }
        return out
    }

    /** The recognized render kinds a hint may name; null for an unknown token (→ fall through). */
    private fun recognizedKind(hint: String): FormatKind? =
        when (hint.lowercase()) {
            "chart" -> FormatKind.CHART
            "table" -> FormatKind.TABLE
            "markdown" -> FormatKind.MARKDOWN
            "plaintext" -> FormatKind.PLAINTEXT
            else -> null
        }
}
