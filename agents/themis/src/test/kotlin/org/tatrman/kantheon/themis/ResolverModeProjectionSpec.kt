package org.tatrman.kantheon.themis

import org.tatrman.kantheon.themis.v1.Themis
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Phase 02 — covers the boundary that ResolverEntitiesOnlySpec bypasses: the
 * REST handler's mode-aware registry projection. The graph never reads
 * `function_specs` in ENTITIES_ONLY mode, but it DOES read `entity_types` to
 * scope the fuzzy matcher namespace — so stripping the whole registry would
 * silently kill domain bindings end-to-end.
 */
class ResolverModeProjectionSpec :
    StringSpec({

        "ENTITIES_ONLY -- function_specs stripped, entity_types preserved" {
            val registry =
                Themis.Registry
                    .newBuilder()
                    .addFunctionSpecs(
                        Themis.FunctionSpec
                            .newBuilder()
                            .setFunctionId("listInvoices"),
                    ).addEntityTypes(
                        Themis.EntityTypeSpec
                            .newBuilder()
                            .setEntityTypeRef("stredisko")
                            .setFuzzyMatcherNamespace("strediska"),
                    ).build()

            val projected =
                projectRegistryForMode(registry, Themis.ResolveMode.RESOLVE_MODE_ENTITIES_ONLY)

            projected.functionSpecsCount shouldBe 0
            projected.entityTypesCount shouldBe 1
            projected.entityTypesList[0].entityTypeRef shouldBe "stredisko"
            projected.entityTypesList[0].fuzzyMatcherNamespace shouldBe "strediska"
        }

        "NORMAL -- registry passed through verbatim" {
            val registry =
                Themis.Registry
                    .newBuilder()
                    .addFunctionSpecs(
                        Themis.FunctionSpec
                            .newBuilder()
                            .setFunctionId("listInvoices"),
                    ).addEntityTypes(
                        Themis.EntityTypeSpec
                            .newBuilder()
                            .setEntityTypeRef("stredisko"),
                    ).build()

            val projected =
                projectRegistryForMode(registry, Themis.ResolveMode.RESOLVE_MODE_NORMAL)

            projected shouldBe registry
        }

        "normalizeMode -- UNSPECIFIED collapses to NORMAL" {
            normalizeMode(Themis.ResolveMode.RESOLVE_MODE_UNSPECIFIED) shouldBe
                Themis.ResolveMode.RESOLVE_MODE_NORMAL
            normalizeMode(Themis.ResolveMode.RESOLVE_MODE_NORMAL) shouldBe
                Themis.ResolveMode.RESOLVE_MODE_NORMAL
            normalizeMode(Themis.ResolveMode.RESOLVE_MODE_ENTITIES_ONLY) shouldBe
                Themis.ResolveMode.RESOLVE_MODE_ENTITIES_ONLY
        }
    })
