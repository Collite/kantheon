package org.tatrman.kantheon.golem.shem

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ariadne.v1.EntityDetail
import org.tatrman.ariadne.v1.ModelBundle
import org.tatrman.ariadne.v1.ModelBundleEntity
import org.tatrman.ariadne.v1.ModelBundleQuery
import org.tatrman.ariadne.v1.ObjectDescriptor
import org.tatrman.ariadne.v1.PackageVersion
import org.tatrman.ariadne.v1.ResolveAreaResponse
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.golem.context.ModelSnapshot
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reconciles the **real** golem-erp Shem bundle (`shems/golem-erp/`, deploy-test WS-C2 T4)
 * against the live `ShemOverlayParser` + assembly contract — mirrors `GolemUcetnictviBundleSpec`
 * / `GolemInvestmentBundleSpec`. Catches drift between the authored bundle and the assembly
 * contract before the pod is deployed (the ahead-of-cluster pattern). The `golem-erp` Shem points
 * at the EXISTING bundled Ariadne `accounting` area (no new model), so the `golem-erp` integration
 * context needs no ariadne image change — see `GolemErpIntegrationSpec`.
 */
class GolemErpBundleSpec :
    StringSpec({

        // golem module working dir is agents/golem; the bundle is checked in beside it.
        val bundle = Path.of("shems/golem-erp")

        "the bundle overlay parses and carries the expected identity + entitlement" {
            val overlay = ShemOverlayParser.parse(Files.readString(bundle.resolve("shem.yaml")))

            overlay.apiVersion shouldBe "kantheon.shem/v1"
            overlay.kind shouldBe "golem-shem"
            overlay.source.id shouldBe "erp"
            overlay.source.label shouldBe "ERP Q&A"
            overlay.source.areas shouldContainExactly listOf("accounting")
            // The entitlement the spec's analyst bearer carries; the outsider is denied (PD-8).
            overlay.overlay.visibilityRoles shouldContain "kantheon-area-accounting"
        }

        "the bundle ships the cs + en prompt sets the PromptStore expects" {
            for (locale in listOf("cs", "en")) {
                for (name in listOf("intent", "free-sql", "chip-topup")) {
                    val p = bundle.resolve("prompts/$locale/$name.yaml")
                    Files.exists(p) shouldBe true
                }
            }
        }

        "the Shem assembles into an AREA_QA AgentCapability for golem-erp over the accounting area" {
            val overlay = ShemOverlayParser.parse(Files.readString(bundle.resolve("shem.yaml")))
            val area =
                ResolveAreaResponse
                    .newBuilder()
                    .addAllPackages(listOf("obchodni_doklady", "ucetnictvi"))
                    .setDescription("Účetnictví a navazující obchodní doklady")
                    .addAllTags(listOf("finance"))
                    .setFound(true)
                    .build()
            val cap = ShemAssembler.assemble(overlay, listOf(area), accountingModel())

            cap.agentKind shouldBe AgentKind.AREA_QA
            cap.agentId shouldBe "golem-erp"
            cap.displayName shouldBe "ERP Q&A"
            cap.areaName shouldBe "accounting"
            // template refs are always present (no per-Shem tool refs on this Shem)
            cap.capabilityRefsList shouldContainAll listOf("theseus.query:v1", "render.table:v1")
        }
    })

/** A minimal accounting-shaped Ariadne model fixture (two entities + one pattern query). */
private fun accountingModel(): ModelSnapshot =
    ModelSnapshot.from(
        ModelBundle
            .newBuilder()
            .addPackageVersions(PackageVersion.newBuilder().setPackageName("obchodni_doklady").setContentHash("h1"))
            .addEntities(entity("faktura", "An invoice document."))
            .addEntities(entity("dobropis", "A credit note."))
            .addPatternQueries(patternQuery("orders_count"))
            .build(),
    )

private fun entity(
    localName: String,
    description: String = "",
    aliases: List<String> = emptyList(),
): ModelBundleEntity =
    ModelBundleEntity
        .newBuilder()
        .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName(localName).setDescription(description))
        .setDetail(EntityDetail.newBuilder().addAllAliases(aliases))
        .build()

private fun patternQuery(localName: String): ModelBundleQuery =
    ModelBundleQuery
        .newBuilder()
        .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName(localName))
        .build()
