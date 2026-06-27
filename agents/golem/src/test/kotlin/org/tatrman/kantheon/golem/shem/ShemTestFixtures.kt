package org.tatrman.kantheon.golem.shem

import org.tatrman.ariadne.v1.EntityDetail
import org.tatrman.ariadne.v1.ModelBundle
import org.tatrman.ariadne.v1.ModelBundleEntity
import org.tatrman.ariadne.v1.ModelBundleQuery
import org.tatrman.ariadne.v1.ObjectDescriptor
import org.tatrman.ariadne.v1.PackageVersion
import org.tatrman.ariadne.v1.ResolveAreaResponse
import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.golem.context.ModelSnapshot

/**
 * A valid `kantheon.shem/v1` overlay (golem-ucetnictvi-shaped) used across the shem
 * specs. Identity + the per-agent residue only — the model-derived fields and the
 * template constants are supplied by [ShemAssembler], not authored here.
 */
internal val VALID_OVERLAY_YAML =
    """
    apiVersion: kantheon.shem/v1
    kind: golem-shem
    source:
      repo: ai-models
      agentDef: agents/ucetnictvi.yaml
      id: ucetnictvi
      label: "Účetnictví"
      areas: [accounting]
    overlay:
      visibility_roles:
        - kantheon-area-accounting
      description_for_router: |
        Účetnictví a navazující obchodní doklady.
      example_questions:
        - "Jaká je účetní hodnota na středisku 4902?"
      counter_examples:
        - "Proč klesla marže?"
      locale_defaults:
        - locale: cs-CZ
          greeting: "Dobrý den"
          date_format: "dd.MM.yyyy"
          currency: "CZK"
    """.trimIndent()

/** The same overlay with `overlay.*` optional keys absent — exercises the assembler defaults. */
internal val MINIMAL_OVERLAY_YAML =
    """
    apiVersion: kantheon.shem/v1
    kind: golem-shem
    source:
      repo: ai-models
      agentDef: agents/ucetnictvi.yaml
      id: ucetnictvi
      label: "Účetnictví"
      areas: [accounting]
    overlay:
      visibility_roles:
        - kantheon-area-accounting
    """.trimIndent()

/** A `ResolveAreaResponse` fixture: packages + description + tags, `found = true`. */
internal fun resolveArea(
    description: String = "Účetnictví a navazující obchodní doklady",
    tags: List<String> = listOf("finance"),
    packages: List<String> = listOf("obchodni_doklady", "ucetnictvi"),
): ResolveAreaResponse =
    ResolveAreaResponse
        .newBuilder()
        .addAllPackages(packages)
        .setDescription(description)
        .addAllTags(tags)
        .setFound(true)
        .build()

private fun entity(
    localName: String,
    description: String = "",
    aliases: List<String> = emptyList(),
): ModelBundleEntity =
    ModelBundleEntity
        .newBuilder()
        .setObjectDescriptor(
            ObjectDescriptor.newBuilder().setLocalName(localName).setDescription(description),
        ).setDetail(EntityDetail.newBuilder().addAllAliases(aliases))
        .build()

private fun patternQuery(localName: String): ModelBundleQuery =
    ModelBundleQuery
        .newBuilder()
        .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName(localName))
        .build()

/**
 * A model with two described entities (one aliased, one bare) plus a no-metadata
 * entity (skipped by the best-effort terminology), and two pattern queries.
 */
internal fun fixtureModel(): ModelSnapshot =
    ModelSnapshot.from(
        ModelBundle
            .newBuilder()
            .addPackageVersions(PackageVersion.newBuilder().setPackageName("ucetnictvi").setContentHash("h1"))
            .addEntities(entity("ucet", description = "Účetní účet", aliases = listOf("account", "konto")))
            .addEntities(entity("obdobi", description = "Účetní období"))
            .addEntities(entity("hodnota")) // no description, no aliases → skipped in terminology
            .addPatternQueries(patternQuery("zustatkyUctu"))
            .addPatternQueries(patternQuery("nezauctovaneDoklady"))
            .build(),
    )

/**
 * Build a [ShemContext] directly from an assembled [AgentCapability] — used by the
 * admission/registration specs (which test over a `ShemContext`, not the parse).
 * Defaults to the ERP-shaped capability the legacy specs asserted against.
 */
internal fun assembledShemContext(
    agentId: String = "golem-erp",
    visibilityRoles: List<String> = listOf("kantheon-area-erp"),
): ShemContext =
    ShemContext(
        AgentCapability
            .newBuilder()
            .setAgentKind(AgentKind.AREA_QA)
            .setAgentId(agentId)
            .setDisplayName("Golem-ERP")
            .setAreaName("ERP")
            .addAllVisibilityRoles(visibilityRoles)
            .build(),
    )
