package org.tatrman.kantheon.pythia.resolve

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.common.v1.EntityBinding
import org.tatrman.kantheon.common.v1.HandoffContext
import org.tatrman.kantheon.common.v1.ViewProvenance
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.InvestigationContext
import org.tatrman.kantheon.pythia.v1.IntentKind
import org.tatrman.kantheon.themis.v1.Themis

private class FakeThemisClient(
    private val response: Themis.ResolveResponse,
) : ThemisClient {
    var lastRequest: Themis.ResolveRequest? = null

    override suspend fun understand(
        request: Themis.ResolveRequest,
        bearer: String,
    ): Themis.ResolveResponse {
        lastRequest = request
        return response
    }
}

/**
 * Stage 2.1 T0/T1 — handoff seeding (PD-1) + Themis outcome mapping (Resolved /
 * Clarify / Refuse). The request uses profile INVESTIGATION_DEEP and threads the
 * typed handoff as prior_context.
 */
class ResolverSpec :
    StringSpec({

        fun investigationWithHandoff(): Investigation =
            Investigation
                .newBuilder()
                .setId("inv-1")
                .setQuestion("why did Maggi revenue drop?")
                .setContext(
                    InvestigationContext
                        .newBuilder()
                        .setLocale("cs")
                        .setHandoff(
                            HandoffContext
                                .newBuilder()
                                .setSourceTurnRef("turn-42")
                                .setView(
                                    ViewProvenance
                                        .newBuilder()
                                        .setPatternId(
                                            "revenueByBrand",
                                        ).setSql("SELECT ...")
                                        .setArgsJson("""{"brand":507}"""),
                                ).addEntities(
                                    EntityBinding
                                        .newBuilder()
                                        .setEntityType(
                                            "brand",
                                        ).setEntityId("507")
                                        .setDisplayLabel("Maggi"),
                                ),
                        ),
                ).build()

        "seedAnchor extracts view + entities + source_turn_ref from the handoff (PD-1)" {
            val resolver = Resolver(FakeThemisClient(Themis.ResolveResponse.getDefaultInstance()))
            val anchor = resolver.seedAnchor(investigationWithHandoff())
            anchor.sourceTurnRef shouldBe "turn-42"
            anchor.queryRef shouldBe "revenueByBrand"
            anchor.sql shouldBe "SELECT ..."
            anchor.entities.single().displayLabel shouldBe "Maggi"
            anchor.isPresent shouldBe true
        }

        "RESOLUTION outcome maps to Resolved with the anchor's entities and INVESTIGATION_DEEP request" {
            runTest {
                val response =
                    Themis.ResolveResponse
                        .newBuilder()
                        .setResolution(
                            Themis.Resolution
                                .newBuilder()
                                .setIntentKind(Themis.IntentKind.RCA)
                                .setArgsJson("""{"channel":"Private"}"""),
                        ).build()
                val client = FakeThemisClient(response)
                val outcome = Resolver(client).resolve(investigationWithHandoff(), "u1")
                outcome.shouldBeInstanceOf<ResolveOutcome.Resolved>()
                outcome.resolution.resolvedIntent.kind shouldBe IntentKind.INTENT_RCA
                outcome.resolution.resolvedIntent.entitiesList
                    .single()
                    .displayLabel shouldBe "Maggi"
                client.lastRequest!!.profile shouldBe Themis.Profile.INVESTIGATION_DEEP
                client.lastRequest!!.hasPriorContext() shouldBe true
            }
        }

        "AWAITING outcome maps to Clarify" {
            runTest {
                val response =
                    Themis.ResolveResponse
                        .newBuilder()
                        .setAwaiting(
                            Themis.AwaitingClarification
                                .newBuilder()
                                .setQuestion("Which Private channel?")
                                .addOptions(
                                    Themis.ClarificationOption
                                        .newBuilder()
                                        .setOptionId("a")
                                        .setLabel("Private A"),
                                ),
                        ).build()
                val outcome = Resolver(FakeThemisClient(response)).resolve(investigationWithHandoff(), "u1")
                outcome.shouldBeInstanceOf<ResolveOutcome.Clarify>()
                outcome.question shouldBe "Which Private channel?"
                outcome.options shouldBe listOf("Private A")
            }
        }

        "REFUSAL outcome maps to Refuse with gap descriptions" {
            runTest {
                val response =
                    Themis.ResolveResponse
                        .newBuilder()
                        .setRefusal(
                            Themis.RefusalWithGaps
                                .newBuilder()
                                .addGaps(
                                    Themis.Gap
                                        .newBuilder()
                                        .setKind(Themis.GapKind.OUT_OF_DATA_SCOPE)
                                        .setDescription("no HR data"),
                                ),
                        ).build()
                val outcome = Resolver(FakeThemisClient(response)).resolve(investigationWithHandoff(), "u1")
                outcome.shouldBeInstanceOf<ResolveOutcome.Refuse>()
                outcome.gaps.single() shouldBe "OUT_OF_DATA_SCOPE: no HR data"
            }
        }
    })
