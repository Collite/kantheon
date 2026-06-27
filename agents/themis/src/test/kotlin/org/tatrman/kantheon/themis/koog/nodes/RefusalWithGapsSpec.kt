package org.tatrman.kantheon.themis.koog.nodes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.themis.client.NlpAnalyzeResult
import org.tatrman.kantheon.themis.koog.DomainSpan
import org.tatrman.kantheon.themis.koog.FuzzyCandidate
import org.tatrman.kantheon.themis.koog.InferenceResult
import org.tatrman.kantheon.themis.koog.ParseState
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Phase 3 Stage 3.4 — the STRICT-mode blocker taxonomy that drives RefusalWithGaps.
 * `collectBlockers` is pure over ResolverContext, so it's unit-tested directly.
 */
class RefusalWithGapsSpec :
    StringSpec({

        val threshold = 0.75

        "ENTITY_UNMAPPED — a domain span with candidates but no fuzzy match" {
            val state =
                contextWith(
                    filtered = listOf(span("Foo", listOf("customer"))),
                    fuzzy = mapOf("0" to emptyList()),
                    confidence = 0.4,
                )
            val gaps = collectBlockers(state, threshold)
            gaps.first().kind shouldBe Themis.GapKind.ENTITY_UNMAPPED
            gaps.first().description shouldContain "Foo"
        }

        "CAPABILITY_UNAVAILABLE — routing produced no usable single agent" {
            val state =
                contextWith(
                    filtered = listOf(span("Shell", listOf("customer"))),
                    fuzzy = mapOf("0" to listOf(fuzzy("customer"))), // entity resolves → no entity gap
                    routing = noAgentRouting(),
                    confidence = 0.9,
                )
            val gaps = collectBlockers(state, threshold)
            gaps shouldHaveSize 1
            gaps.first().kind shouldBe Themis.GapKind.CAPABILITY_UNAVAILABLE
        }

        "AMBIGUOUS_INTENT — low confidence with no structural blocker" {
            val state =
                contextWith(
                    filtered = listOf(span("Shell", listOf("customer"))),
                    fuzzy = mapOf("0" to listOf(fuzzy("customer"))),
                    routing = chosenRouting("golem-erp"),
                    confidence = 0.4,
                )
            val gaps = collectBlockers(state, threshold)
            gaps shouldHaveSize 1
            gaps.first().kind shouldBe Themis.GapKind.AMBIGUOUS_INTENT
        }

        "structural blockers take precedence over ambiguity" {
            val state =
                contextWith(
                    filtered = listOf(span("Foo", listOf("customer"))),
                    fuzzy = mapOf("0" to emptyList()),
                    routing = noAgentRouting(),
                    confidence = 0.2,
                )
            val gaps = collectBlockers(state, threshold)
            // entity + capability gaps, but NOT a bare ambiguity gap on top.
            gaps.map { it.kind } shouldBe
                listOf(Themis.GapKind.ENTITY_UNMAPPED, Themis.GapKind.CAPABILITY_UNAVAILABLE)
        }

        "no blockers when everything resolves confidently" {
            val state =
                contextWith(
                    filtered = listOf(span("Shell", listOf("customer"))),
                    fuzzy = mapOf("0" to listOf(fuzzy("customer"))),
                    routing = chosenRouting("golem-erp"),
                    confidence = 0.95,
                )
            collectBlockers(state, threshold).shouldBeEmpty()
        }
    })

private fun contextWith(
    filtered: List<DomainSpan>,
    fuzzy: Map<String, List<FuzzyCandidate>>,
    routing: Themis.RoutingDecision? = null,
    confidence: Double,
): ResolverContext =
    ResolverContext(
        conversationId = "c-1",
        parseState =
            ParseState(
                nlpResponse = emptyParse(),
                filteredSpans = filtered,
                fuzzyMatches = fuzzy,
                routingDecision = routing,
                inferenceResult =
                    InferenceResult("f", "{}", emptyList(), confidence, emptyList(), "r"),
            ),
    )

private fun span(
    text: String,
    candidates: List<String>,
): DomainSpan =
    DomainSpan(
        charStart = 0,
        charEnd = text.length,
        coveredText = text,
        pos = "PROPN",
        depHead = 0,
        depRelation = "nsubj",
        entityTypeCandidates = candidates,
    )

private fun fuzzy(ref: String): FuzzyCandidate = FuzzyCandidate("id-1", "Label", 0.9, ref)

private fun chosenRouting(agent: String): Themis.RoutingDecision =
    Themis.RoutingDecision
        .newBuilder()
        .setChosenAgentId(
            org.tatrman.kantheon.common.v1.AgentId
                .newBuilder()
                .setValue(agent),
        ).setLayerHit(1)
        .build()

private fun noAgentRouting(): Themis.RoutingDecision =
    Themis.RoutingDecision
        .newBuilder()
        .setNeedsUserPick(true)
        .setLayerHit(1)
        .build()

private fun emptyParse() =
    NlpAnalyzeResult("cs", 0.99, "stanza", emptyList(), emptyList(), emptyList(), emptyList(), "t", 1, emptyList())
