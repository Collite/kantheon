package org.tatrman.kantheon.iris.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.slf4j.LoggerFactory

/**
 * Curated static chips (Stage 3.2 T3) — the suggested-topic strip shown on a
 * fresh session / empty input. Loaded once from a classpath YAML the executor
 * ships with manifest-derived defaults (`static-chips.yaml`); Bora edits the
 * content later with no code change. Best-effort: a missing/garbled file yields
 * no chips (logged), never an error.
 *
 * Role-filtering against `visibility_roles` (PD-8) lands with the discovery
 * surface (Phase 4 Stage 4.3), where the caller's roles are available; here the
 * curated list is global.
 *
 * ```yaml
 * chips:
 *   - display: "Tržby za měsíc"
 *     prompt:  "Kolik jsme prodali minulý měsíc?"
 * ```
 */
class StaticChipSource(
    resourcePath: String = "/static-chips.yaml",
) {
    private val log = LoggerFactory.getLogger(StaticChipSource::class.java)
    private val chips: List<SessionChipDto> = load(resourcePath)

    fun chips(): List<SessionChipDto> = chips

    private fun load(resourcePath: String): List<SessionChipDto> =
        try {
            val stream = javaClass.getResourceAsStream(resourcePath) ?: return emptyList()
            val root = stream.use { ObjectMapper(YAMLFactory()).readTree(it) } ?: return emptyList()
            val chipsNode = root.get("chips") ?: return emptyList()
            chipsNode.mapNotNull { node ->
                val display = node.get("display")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val prompt = node.get("prompt")?.asText()?.takeIf { it.isNotBlank() } ?: display
                SessionChipDto(display = display, prompt = prompt, source = "static")
            }
        } catch (e: Throwable) {
            log.warn("static-chips load failed (best-effort; no curated chips): {}", e.message)
            emptyList()
        }
}
