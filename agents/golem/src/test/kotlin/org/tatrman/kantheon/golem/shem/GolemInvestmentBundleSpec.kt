package org.tatrman.kantheon.golem.shem

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.EntityDetail
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleEntity
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.PackageVersion
import org.tatrman.meta.v1.ResolveAreaResponse
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.golem.context.ModelSnapshot
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reconciles the **real** golem-investment Shem bundle (`shems/golem-investment/`,
 * Midas Stage 3.1) against the live `ShemOverlayParser` + assembly contract — mirrors
 * `GolemUcetnictviBundleSpec`. Catches drift between the authored bundle and the
 * assembly contract before the pod is deployed (the ahead-of-cluster pattern). The
 * ai-models `investment` area + `q.midas.*` queries (T1/T2) are exercised live against
 * Veles in Stream T; here the assembly is proven against fixtures.
 */
class GolemInvestmentBundleSpec :
    StringSpec({

        // golem module working dir is agents/golem; the bundle is checked in beside it.
        val bundle = Path.of("shems/golem-investment")

        "the bundle overlay parses and carries the expected identity + entitlement + tool refs" {
            val overlay = ShemOverlayParser.parse(Files.readString(bundle.resolve("shem.yaml")))

            overlay.apiVersion shouldBe "kantheon.shem/v1"
            overlay.kind shouldBe "golem-shem"
            overlay.source.id shouldBe "investment"
            overlay.source.label shouldBe "Investment Q&A"
            overlay.source.areas shouldContainExactly listOf("investment")
            overlay.overlay.visibilityRoles shouldContain "kantheon-area-investment"
            // the five midas calc tools are declared as per-Shem capability refs
            overlay.overlay.capabilityRefs shouldContainExactly
                listOf(
                    "midas.portfolio.performance:v1",
                    "midas.position.valuation:v1",
                    "midas.position.cost_basis:v1",
                    "midas.transaction.fee_allocation:v1",
                    "midas.reconcile.statement:v1",
                )
        }

        "the bundle ships the cs + en prompt sets the PromptStore expects" {
            for (locale in listOf("cs", "en")) {
                for (name in listOf("intent", "free-sql", "chip-topup")) {
                    val p = bundle.resolve("prompts/$locale/$name.yaml")
                    Files.exists(p) shouldBe true
                }
            }
        }

        "the Shem assembles into an AREA_QA AgentCapability with the model-derived + tool fields" {
            val overlay = ShemOverlayParser.parse(Files.readString(bundle.resolve("shem.yaml")))
            val area =
                ResolveAreaResponse
                    .newBuilder()
                    .addAllPackages(listOf("investment"))
                    .setDescription("Investment portfolios")
                    .addAllTags(listOf("finance"))
                    .setFound(true)
                    .build()
            val cap = ShemAssembler.assemble(overlay, listOf(area), investmentModel())

            cap.agentKind shouldBe AgentKind.AREA_QA
            cap.agentId shouldBe "golem-investment"
            cap.displayName shouldBe "Investment Q&A"
            cap.areaName shouldBe "investment"

            // (2) model-derived: five entities, five preferred queries, five terminology terms
            cap.areaEntitiesList shouldContainExactly
                listOf("clients", "portfolios", "assets", "transactions", "position")
            cap.preferredQueriesList shouldContainExactly
                listOf(
                    "positions_current",
                    "transactions_recent",
                    "dividends_period",
                    "fees_period",
                    "realised_pnl_period",
                )
            cap.areaTerminologyList.size shouldBe 5
            cap.areaTerminologyList.map { it.term } shouldContain "position"

            // template refs + the five overlay-declared midas tool refs
            cap.capabilityRefsList shouldContainAll
                listOf("query.query:v1", "render.table:v1")
            cap.capabilityRefsList shouldContainAll
                listOf(
                    "midas.portfolio.performance:v1",
                    "midas.position.valuation:v1",
                    "midas.position.cost_basis:v1",
                    "midas.transaction.fee_allocation:v1",
                    "midas.reconcile.statement:v1",
                )
        }
    })

/** An investment-shaped Veles model fixture: the five area entities (described) + five queries. */
private fun investmentModel(): ModelSnapshot =
    ModelSnapshot.from(
        ModelBundle
            .newBuilder()
            .addPackageVersions(PackageVersion.newBuilder().setPackageName("investment").setContentHash("h1"))
            .addEntities(entity("clients", "A portfolio owner (the investor).", listOf("client", "investor")))
            .addEntities(entity("portfolios", "A book of holdings in a base currency."))
            .addEntities(entity("assets", "A tradable instrument (equity, bond, fund)."))
            .addEntities(entity("transactions", "A buy/sell/dividend/fee event."))
            .addEntities(entity("position", "A current holding (quantity + market value)."))
            .addPatternQueries(patternQuery("positions_current"))
            .addPatternQueries(patternQuery("transactions_recent"))
            .addPatternQueries(patternQuery("dividends_period"))
            .addPatternQueries(patternQuery("fees_period"))
            .addPatternQueries(patternQuery("realised_pnl_period"))
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
