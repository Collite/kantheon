package org.tatrman.kantheon.golem.format

import kotlinx.serialization.json.JsonArray
import org.tatrman.ariadne.v1.DrillMapDetail
import org.tatrman.kantheon.envelope.v1.Drilldown
import org.tatrman.kantheon.golem.context.ModelSnapshot

/**
 * Drilldown builder — a faithful port of ai-platform `drilldowns.py`. Two sources:
 *   * **`explicit_ttr`** — the model's declared drill map for the picked pattern;
 *   * **`auto_overlap`** — catalog patterns every required param of which matches a
 *     result column by name (case-insensitive), excluding the picked pattern,
 *     already-explicit targets, and any target an explicit drill marks `override_auto`.
 */
object Drilldowns {
    fun derive(
        pickedPatternId: String?,
        rows: JsonArray,
        model: ModelSnapshot?,
        locale: String = "cs",
    ): List<Drilldown> {
        if (model == null || pickedPatternId.isNullOrBlank()) return emptyList()
        val pickedQname = model.patternQuery(pickedPatternId)?.objectDescriptor?.qualifiedName ?: return emptyList()
        val explicit = model.drillsFrom(pickedQname)

        val out = mutableListOf<Drilldown>()
        val explicitTargets = HashSet<String>()
        val suppressAuto = HashSet<String>()
        for (spec in explicit) {
            val target = spec.toPattern.name
            explicitTargets += target
            if (spec.overrideAuto) suppressAuto += target
            out += explicitDrill(spec, target, locale)
        }

        // auto_overlap — over uppercased result columns.
        val resultCols = RowUtil.columnNames(rows).map { it.uppercase() }.toSet()
        if (resultCols.isNotEmpty()) {
            for (pq in model.patternQueries) {
                val target = pq.objectDescriptor.localName
                if (target == pickedPatternId || target in explicitTargets || target in suppressAuto) continue
                val required = pq.parametersList.filter { !it.optional }
                if (required.isEmpty()) continue
                // Every required param must map to a result column (uppercased name equality).
                val mapping = LinkedHashMap<String, String>()
                var ok = true
                for (p in required) {
                    val col = resultCols.firstOrNull { it == p.name.uppercase() }
                    if (col == null) {
                        ok = false
                        break
                    }
                    mapping[p.name] = col
                }
                if (!ok) continue
                out +=
                    Drilldown
                        .newBuilder()
                        .setId("auto_$target")
                        .setDisplay("Detail — ${pq.queryDescriptor.objectDescriptor.localName.ifBlank { target }}")
                        .setTargetPatternId(target)
                        .putAllArgMapping(mapping)
                        .setScope("row")
                        .setSource("auto_overlap")
                        .build()
            }
        }
        return out
    }

    private fun explicitDrill(
        spec: DrillMapDetail,
        target: String,
        locale: String,
    ): Drilldown =
        Drilldown
            .newBuilder()
            .setId(spec.name.ifBlank { "explicit_$target" })
            .setDisplay(displayFor(spec, target, locale))
            .setTargetPatternId(target)
            .putAllArgMapping(spec.argMappingMap)
            .setScope("row")
            .setSource("explicit_ttr")
            .build()

    private fun displayFor(
        spec: DrillMapDetail,
        target: String,
        locale: String,
    ): String {
        val byLang = spec.display.byLanguageMap
        return byLang[locale] ?: byLang.values.firstOrNull() ?: "Detail $target"
    }
}
