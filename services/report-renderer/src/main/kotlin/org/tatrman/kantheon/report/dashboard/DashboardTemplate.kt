package org.tatrman.kantheon.report.dashboard

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.LocalDate
import java.time.ZoneOffset

/** A Midas-contributed dashboard template (Stage 3.5 content) for the generic Iris system.
 *  Pane `source` maps carry the `ViewProvenance` fields the generic refresh replays. */
data class DashboardTemplate(
    val templateId: String = "",
    val displayName: String = "",
    val version: String = "",
    val params: List<DashboardParam> = emptyList(),
    val panes: List<DashboardPane> = emptyList(),
)

data class DashboardParam(
    val name: String = "",
    val kind: String = "",
    val required: Boolean = false,
    val default: String = "",
)

data class DashboardPane(
    val id: String = "",
    val kind: String = "",
    val title: String = "",
    val source: Map<String, String> = emptyMap(),
)

/** Loads + resolves Midas dashboard-template content. The generic loader (Iris-side) accepts
 *  the same shape; here Midas owns the content + the `{param}` / `{period.end}` interpolation. */
object DashboardTemplateLoader {
    private val mapper: ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun parse(yaml: String): DashboardTemplate = mapper.readValue(yaml)

    fun fromClasspath(
        path: String,
        loader: ClassLoader = DashboardTemplateLoader::class.java.classLoader,
    ): DashboardTemplate? = loader.getResourceAsStream(path)?.use { parse(it.readBytes().decodeToString()) }
}

/**
 * Resolves a dashboard template's panes against a `{client, portfolio, period}` fill (Stage
 * 3.5 T5): applies declared defaults, then interpolates `{param}` (and the derived
 * `{period.end}`) into every pane `source` value, so each pane's `ViewProvenance` is fully
 * bound before the generic system replays it. Unknown placeholders are left intact (surfaced
 * as a per-pane error by the generic refresh rather than silently blanked).
 */
object DashboardParamResolver {
    private val PLACEHOLDER = Regex("\\{([a-zA-Z0-9_.]+)}")

    fun resolve(
        template: DashboardTemplate,
        args: Map<String, String>,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): List<DashboardPane> {
        val filled = template.params.associate { it.name to (args[it.name]?.ifBlank { null } ?: it.default) }
        val period = filled["period"].orEmpty().ifBlank { "ytd" }
        val values = filled + ("period.end" to periodEnd(period, today))
        return template.panes.map { pane ->
            pane.copy(source = pane.source.mapValues { (_, v) -> interpolate(v, values) })
        }
    }

    private fun interpolate(
        text: String,
        values: Map<String, String?>,
    ): String =
        PLACEHOLDER.replace(text) { m ->
            val key = m.groupValues[1]
            values[key]?.takeIf { it.isNotBlank() } ?: m.value // leave unknown placeholders intact
        }

    /** A relative period's end date. ytd/mtd/qtd/all resolve to `today`; an explicit `a..b` → b. */
    private fun periodEnd(
        period: String,
        today: LocalDate,
    ): String = if (period.contains("..")) period.substringAfter("..").trim() else today.toString()
}
