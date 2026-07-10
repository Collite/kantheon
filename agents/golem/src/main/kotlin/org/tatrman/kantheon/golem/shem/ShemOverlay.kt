package org.tatrman.kantheon.golem.shem

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/** A Shem overlay failed to parse or violated a discipline rule. Fatal at boot. */
class ShemValidationException(
    message: String,
) : RuntimeException(message)

/**
 * The on-disk `kantheon.shem/v1` overlay (`<golem.shem.dir>/shem.yaml`). Unlike the
 * retired rich `ShemYaml`, this carries only the four-source-assembly *inputs* that
 * kantheon owns: identity (`source`) + the per-agent residue (`overlay`). The
 * model-derived fields (`area_entities` / `preferred_queries` / `area_terminology`)
 * and the template constants are NOT authored here — [ShemAssembler] supplies them.
 */
data class ShemOverlay(
    // Top-level keys are authored camelCase (`apiVersion`); the SNAKE_CASE strategy
    // governs the `overlay.*` residue (`visibility_roles`, `date_format`) — so the
    // mixed-casing keys are pinned explicitly with @JsonProperty.
    @param:JsonProperty("apiVersion") val apiVersion: String = "",
    val kind: String = "",
    val source: ShemSource = ShemSource(),
    val overlay: ShemOverlayBlock = ShemOverlayBlock(),
)

data class ShemSource(
    val repo: String = "",
    @param:JsonProperty("agentDef") val agentDef: String = "",
    val id: String = "",
    val label: String = "",
    val areas: List<String> = emptyList(),
)

data class ShemOverlayBlock(
    val visibilityRoles: List<String> = emptyList(),
    val descriptionForRouter: String = "",
    val exampleQuestions: List<String> = emptyList(),
    val counterExamples: List<String> = emptyList(),
    val localeDefaults: List<LocaleDefaultYaml> = emptyList(),
    // Optional per-Shem tool capability refs, appended to the template constants by
    // [ShemAssembler]. Most Golems leave this empty (they reach data through the template
    // query/render refs); a Golem fronting a service with bespoke MCP tools declares them
    // here so they ride the registered AgentCapability — e.g. golem-investment → midas.*:v1.
    val capabilityRefs: List<String> = emptyList(),
)

data class LocaleDefaultYaml(
    val locale: String = "",
    val greeting: String = "",
    val dateFormat: String = "",
    val currency: String = "",
)

/**
 * Parses + lints a Golem pod's `kantheon.shem/v1` overlay YAML. Golem-local on
 * purpose (a server-side `ManifestYamlLoader` would couple `agents/golem` to
 * `tools/capabilities-mcp`). Strict-fail discipline: a malformed overlay or a
 * missing required key is fatal at boot — there is no partial Shem.
 */
object ShemOverlayParser {
    private const val EXPECTED_API_VERSION = "kantheon.shem/v1"
    private const val EXPECTED_KIND = "golem-shem"

    private val mapper: ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun parse(yaml: String): ShemOverlay {
        val dto =
            try {
                mapper.readValue<ShemOverlay>(yaml)
            } catch (e: Exception) {
                throw ShemValidationException("Shem overlay is not parseable: ${e.message}")
            }
        dto.lint()
        return dto
    }

    private fun ShemOverlay.lint() {
        lint(apiVersion == EXPECTED_API_VERSION) {
            "apiVersion must be '$EXPECTED_API_VERSION' (was '$apiVersion')"
        }
        lint(kind == EXPECTED_KIND) { "kind must be '$EXPECTED_KIND' (was '$kind')" }
        lint(source.id.isNotBlank()) { "source.id must be non-blank" }
        lint(source.label.isNotBlank()) { "source.label must be non-blank" }
        lint(source.areas.isNotEmpty()) { "source.areas must list at least one area" }
    }

    /** Discipline-lint assertion — failures surface as [ShemValidationException], not bare IAE. */
    private inline fun lint(
        ok: Boolean,
        message: () -> String,
    ) {
        if (!ok) throw ShemValidationException(message())
    }
}
