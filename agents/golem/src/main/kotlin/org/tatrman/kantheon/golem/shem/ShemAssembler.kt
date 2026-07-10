package org.tatrman.kantheon.golem.shem

import org.tatrman.meta.v1.ResolveAreaResponse
import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.HitlProfile
import org.tatrman.kantheon.capabilities.v1.IntentKind
import org.tatrman.kantheon.capabilities.v1.LocaleDefault
import org.tatrman.kantheon.capabilities.v1.TermDef
import org.tatrman.kantheon.golem.context.ModelSnapshot

/**
 * Assembles a Golem's `AgentCapability` (`agent_kind == AREA_QA`) from the four
 * converged-design sources (golem/contracts §6):
 *
 *  1. the ai-models agent definition (carried in the overlay `source` block),
 *  2. the Ariadne model — `area_entities` / `preferred_queries` / `area_terminology`,
 *  3. the overlay residue (`visibility_roles`, optional router seed / examples / locales),
 *  4. the Golem-template constants (`agent_kind`, `intent_kinds`, capability refs, hitl, endpoint).
 *
 * A pure function over already-fetched inputs so it is unit-testable without gRPC —
 * [org.tatrman.kantheon.golem.context.GolemModelSubsystem] does the I/O (resolveArea +
 * getModel) and hands the results here. `typical_latency_ms` / `typical_cost_usd` stay 0
 * (measured in Stage 4.4, not authored).
 */
object ShemAssembler {
    /** Template constants — same for every Golem pod regardless of Shem. */
    private val INTENT_KINDS = listOf(IntentKind.PROCEDURAL)
    private val CAPABILITY_REFS =
        listOf(
            "theseus.query:v1",
            "theseus.compile:v1",
            "render.table:v1",
            "render.chart:v1",
        )
    private val HITL_DEFAULT = HitlProfile.INTERACTIVE
    private const val HEALTH_CHECK_PATH = "/health"

    /** Template locale defaults when the overlay omits `locale_defaults`. */
    private val TEMPLATE_LOCALE_DEFAULTS =
        listOf(
            localeDefault("cs-CZ", "Dobrý den, jak vám mohu pomoci?", "dd.MM.yyyy", "CZK"),
            localeDefault("en", "Hi, how can I help?", "yyyy-MM-dd", "EUR"),
        )

    /**
     * @param overlay     the parsed `kantheon.shem/v1` overlay (identity + residue).
     * @param areaResults the `ResolveArea` result per `source.areas` entry, in order.
     * @param model       the loaded Ariadne model (entities + pattern queries) for the area packages.
     */
    fun assemble(
        overlay: ShemOverlay,
        areaResults: List<ResolveAreaResponse>,
        model: ModelSnapshot,
    ): AgentCapability {
        val areaEntities =
            model.entities
                .map { it.objectDescriptor.localName }
                .filter { it.isNotBlank() }
                .distinct()
        val preferredQueries =
            model.patternQueries
                .map { it.objectDescriptor.localName }
                .filter { it.isNotBlank() }
                .distinct()

        return identityBuilder(overlay, areaResults)
            // ── (2) model-derived ──
            .addAllAreaEntities(areaEntities)
            .addAllPreferredQueries(preferredQueries)
            .addAllAreaTerminology(terminology(model))
            .build()
    }

    /**
     * The overlay-only (identity + residue + template constants) capability, with the
     * model-derived fields left empty. Used as the pre-load placeholder so the admission
     * gate + registration can hold a stable [ShemContext] before Ariadne's model loads;
     * [assemble] supplies the same fields plus the model-derived ones.
     */
    fun identity(
        overlay: ShemOverlay,
        areaResults: List<ResolveAreaResponse> = emptyList(),
    ): AgentCapability = identityBuilder(overlay, areaResults).build()

    private fun identityBuilder(
        overlay: ShemOverlay,
        areaResults: List<ResolveAreaResponse>,
    ): AgentCapability.Builder {
        val id = overlay.source.id
        // Template refs + any per-Shem tool refs the overlay declares (e.g. midas.*:v1),
        // de-duplicated, order-preserving (template constants first).
        val capabilityRefs = (CAPABILITY_REFS + overlay.overlay.capabilityRefs).distinct()
        return AgentCapability
            .newBuilder()
            // ── template constants (+ overlay-declared tool refs) ──
            .setAgentKind(AgentKind.AREA_QA)
            .addAllIntentKindsSupported(INTENT_KINDS)
            .addAllCapabilityRefs(capabilityRefs)
            .addAllPreferredCapabilities(capabilityRefs)
            .setHitlDefault(HITL_DEFAULT)
            .setServiceEndpoint("http://golem-$id.kantheon.svc.cluster.local:7420")
            .setHealthCheckPath(HEALTH_CHECK_PATH)
            // ── (1) identity from the overlay `source` block ──
            .setAgentId("golem-$id")
            .setDisplayName(overlay.source.label)
            .setAreaName(overlay.source.areas.joinToString(","))
            // ── (3) overlay residue ──
            .addAllVisibilityRoles(overlay.overlay.visibilityRoles)
            .setDescriptionForRouter(descriptionForRouter(overlay, areaResults))
            .addAllExampleQuestions(overlay.overlay.exampleQuestions)
            .addAllCounterExamples(overlay.overlay.counterExamples)
            .addAllLocaleDefaults(localeDefaults(overlay))
    }

    /** Overlay seed wins; else join each resolved area's `description (tags)`. */
    private fun descriptionForRouter(
        overlay: ShemOverlay,
        areaResults: List<ResolveAreaResponse>,
    ): String {
        if (overlay.overlay.descriptionForRouter.isNotBlank()) return overlay.overlay.descriptionForRouter
        return areaResults
            .filter { it.description.isNotBlank() }
            .joinToString(" ") { area ->
                val tags = area.tagsList.joinToString(", ")
                if (tags.isNotBlank()) "${area.description} ($tags)" else area.description
            }
    }

    private fun localeDefaults(overlay: ShemOverlay): List<LocaleDefault> =
        if (overlay.overlay.localeDefaults.isNotEmpty()) {
            overlay.overlay.localeDefaults.map {
                localeDefault(it.locale, it.greeting, it.dateFormat, it.currency)
            }
        } else {
            TEMPLATE_LOCALE_DEFAULTS
        }

    /**
     * Best-effort terminology — one [TermDef] per entity that carries a description
     * or aliases (entities with neither are skipped). `term` = entity local name,
     * `definition` = its description, `synonyms` = `EntityDetail.aliases`.
     */
    private fun terminology(model: ModelSnapshot): List<TermDef> =
        model.entities.mapNotNull { entity ->
            val term = entity.objectDescriptor.localName
            if (term.isBlank()) return@mapNotNull null
            val definition = entity.objectDescriptor.description
            val aliases = entity.detail.aliasesList
            if (definition.isBlank() && aliases.isEmpty()) {
                null
            } else {
                TermDef
                    .newBuilder()
                    .setTerm(term)
                    .setDefinition(definition)
                    .addAllSynonyms(aliases)
                    .build()
            }
        }

    private fun localeDefault(
        locale: String,
        greeting: String,
        dateFormat: String,
        currency: String,
    ): LocaleDefault =
        LocaleDefault
            .newBuilder()
            .setLocale(locale)
            .setGreeting(greeting)
            .setDateFormat(dateFormat)
            .setCurrency(currency)
            .build()
}
