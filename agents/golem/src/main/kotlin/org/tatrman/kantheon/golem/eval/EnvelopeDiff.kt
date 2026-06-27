package org.tatrman.kantheon.golem.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import kotlin.math.abs
import kotlin.math.max

/** How a field-level divergence is judged (contracts §8 / S3.3 T3). */
enum class DivergenceClass {
    /** Golem is wrong — must be fixed before the parity gate is green. */
    BUG,

    /** An intended kantheon difference (typed TableDetails, provenance, InvestigateChip, …). */
    ACCEPTABLE,
}

/** One field-level difference between the recorded v2 envelope and Golem's. */
data class Divergence(
    val field: String,
    val expected: String,
    val actual: String,
    val cls: DivergenceClass,
    val note: String = "",
)

/**
 * Field-wise envelope diff for the parity harness (S3.3). Compares the semantic surface — render
 * kind, row content, chip/drilldown sources, `current_view.total_rows`, `plan_source` — and
 * classifies each difference. Kantheon's deliberate additions (typed `TableDetails`, the
 * `InvestigateChip`, provenance) are tolerated as `ACCEPTABLE` so the gate flags only real bugs.
 */
object EnvelopeDiff {
    private val json = Json { ignoreUnknownKeys = true }

    fun diff(
        expected: FormatEnvelope,
        actual: FormatEnvelope,
    ): List<Divergence> {
        val out = mutableListOf<Divergence>()

        // 1. Render kind.
        if (expected.format.kind != FormatKind.FORMAT_KIND_UNSPECIFIED && expected.format.kind != actual.format.kind) {
            out += Divergence("format.kind", expected.format.kind.name, actual.format.kind.name, DivergenceClass.BUG)
        }

        // 2. Row content — compare row count + column keys of the first row.
        diffContent(expected, actual)?.let { out += it }

        // 3. plan_source.
        if (expected.planSource != actual.planSource) {
            out += Divergence("plan_source", expected.planSource.name, actual.planSource.name, DivergenceClass.BUG)
        }

        // 4. current_view.total_rows.
        if (expected.hasCurrentView() &&
            expected.currentView.hasTotalRows() &&
            expected.currentView.totalRows != actual.currentView.totalRows
        ) {
            out +=
                Divergence(
                    "current_view.total_rows",
                    expected.currentView.totalRows.toString(),
                    actual.currentView.totalRows.toString(),
                    DivergenceClass.BUG,
                )
        }

        // 5. Chips — by source. A missing expected source is a bug; an extra source (e.g. the
        //    kantheon-net-new `investigate`) is acceptable.
        out += diffSources("chips", expectedChipSources(expected), actualChipSources(actual))

        // 6. Drilldowns — by source.
        out +=
            diffSources(
                "drilldowns",
                expected.drilldownsList.map { it.source }.toSet(),
                actual.drilldownsList.map { it.source }.toSet(),
            )

        // 7. Typed TableDetails — column-directive count. MORE directives on Golem's side is the Δ5
        //    addition (ACCEPTABLE); FEWER means Golem dropped directives v2 had (a real regression —
        //    e.g. a lost right-align / `%.2f`), so flag it as a BUG.
        when {
            actual.format.table.columnsCount > expected.format.table.columnsCount ->
                out +=
                    Divergence(
                        "format.table.columns",
                        "${expected.format.table.columnsCount} cols",
                        "${actual.format.table.columnsCount} cols (typed)",
                        DivergenceClass.ACCEPTABLE,
                        "Δ5 typed column-spec emitter (numeric→right / float→number+%.2f)",
                    )
            actual.format.table.columnsCount < expected.format.table.columnsCount ->
                out +=
                    Divergence(
                        "format.table.columns",
                        "${expected.format.table.columnsCount} cols",
                        "${actual.format.table.columnsCount} cols",
                        DivergenceClass.BUG,
                        "lost column directives vs v2",
                    )
        }

        return out
    }

    private fun diffContent(
        expected: FormatEnvelope,
        actual: FormatEnvelope,
    ): Divergence? {
        val expRows = rows(expected.contentJson)
        val actRows = rows(actual.contentJson)
        if (expRows == null || actRows == null) return null
        if (expRows.size != actRows.size) {
            return Divergence("content.rowCount", expRows.size.toString(), actRows.size.toString(), DivergenceClass.BUG)
        }
        // Value-level comparison, per row, per cell — a structural (key-only) check would let wrong
        // numbers / swapped values / bad rounding through. Real JSON numbers compare with a relative
        // float tolerance (rounding parity); strings (codes, labels) compare exactly. Key sets are
        // compared order-independently.
        for (i in expRows.indices) {
            val e = expRows[i] as? JsonObject ?: continue
            val a = actRows[i] as? JsonObject ?: continue
            if (e.keys != a.keys) {
                return Divergence(
                    "content[$i].columns",
                    e.keys.joinToString(),
                    a.keys.joinToString(),
                    DivergenceClass.BUG,
                )
            }
            for (k in e.keys) {
                if (!cellsEqual(e[k], a[k])) {
                    return Divergence(
                        "content[$i].$k",
                        canon(e[k]),
                        canon(a[k]),
                        DivergenceClass.BUG,
                        "row value mismatch",
                    )
                }
            }
        }
        return null
    }

    /** Real JSON numbers compare with a relative tolerance; everything else by canonical string. */
    private fun cellsEqual(
        e: JsonElement?,
        a: JsonElement?,
    ): Boolean {
        if (e == a) return true
        val en = numericValue(e)
        val an = numericValue(a)
        if (en != null && an != null) return abs(en - an) <= TOLERANCE * max(1.0, max(abs(en), abs(an)))
        return canon(e) == canon(a)
    }

    /** The double value of a real (non-string) JSON number, or null for strings / non-primitives. */
    private fun numericValue(el: JsonElement?): Double? =
        (el as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

    private fun canon(el: JsonElement?): String = (el as? JsonPrimitive)?.content ?: el.toString()

    private const val TOLERANCE = 1e-9

    private fun diffSources(
        field: String,
        expected: Set<String>,
        actual: Set<String>,
    ): List<Divergence> {
        val out = mutableListOf<Divergence>()
        (expected - actual).forEach {
            out += Divergence("$field.source", it, "(absent)", DivergenceClass.BUG, "expected source missing")
        }
        (actual - expected).forEach {
            // Extra sources are kantheon additions (e.g. `investigate`) — acceptable.
            out += Divergence("$field.source", "(absent)", it, DivergenceClass.ACCEPTABLE, "kantheon-added source")
        }
        return out
    }

    private fun expectedChipSources(e: FormatEnvelope): Set<String> =
        e.chipsList
            .mapNotNull { if (it.hasPrompt()) it.prompt.source else null }
            .filter { it.isNotBlank() }
            .toSet()

    private fun actualChipSources(a: FormatEnvelope): Set<String> {
        val out = mutableSetOf<String>()
        a.chipsList.forEach {
            when {
                it.hasPrompt() && it.prompt.source.isNotBlank() -> out += it.prompt.source
                it.hasInvestigate() -> out += "investigate"
                it.hasRouting() -> out += "routing"
            }
        }
        return out
    }

    private fun rows(contentJson: String): List<*>? =
        if (contentJson.isBlank()) {
            null
        } else {
            runCatching { json.parseToJsonElement(contentJson) as? JsonArray }.getOrNull()
        }
}
