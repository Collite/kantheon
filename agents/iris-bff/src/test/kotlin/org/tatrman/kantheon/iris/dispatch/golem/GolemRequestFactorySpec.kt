package org.tatrman.kantheon.iris.dispatch.golem

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.common.v1.EntityBinding
import org.tatrman.kantheon.common.v1.HandoffContext
import org.tatrman.kantheon.common.v1.ViewProvenance
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.dispatch.AgentTurn
import org.tatrman.kantheon.iris.domain.TurnRecord
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.themis.v1.Themis.IntentKind
import org.tatrman.kantheon.themis.v1.Themis.Resolution
import java.time.Instant
import java.util.UUID

class GolemRequestFactorySpec :
    StringSpec({

        val sessionId = UUID.randomUUID()

        fun turn(
            handoff: HandoffContext? = null,
            resolution: Resolution? = null,
        ) = AgentTurn(
            turnId = "turn-1",
            sessionId = sessionId,
            caller = CallerIdentity("maya", "hartland", "jwt-1"),
            correlationId = "corr-9",
            question = "Kolik jsme prodali v lednu?",
            handoff = handoff,
            resolution = resolution,
        )

        fun priorTurn(
            seq: Int,
            question: String,
            status: TurnStatus = TurnStatus.DONE,
            envelopeJson: String? = null,
        ) = TurnRecord(
            turnId = UUID.randomUUID(),
            sessionId = sessionId,
            seq = seq,
            agentId = "golem-hartland",
            question = question,
            status = status,
            envelopeJson = envelopeJson,
            createdAt = Instant.EPOCH,
        )

        "the golem_id is the routed agent id — golem rejects any other" {
            val request = GolemRequestFactory.forTurn("golem-hartland", turn(), "cs")

            request.golemId shouldBe "golem-hartland"
            request.id shouldBe "turn-1"
            request.question shouldBe "Kolik jsme prodali v lednu?"
            request.context.locale shouldBe "cs"
            request.caller.userId shouldBe "maya"
            request.caller.tenantId shouldBe "hartland"
            request.caller.correlationId shouldBe "corr-9"
        }

        "Themis's Resolution rides as resolved_intent (trust-upstream)" {
            val resolution =
                Resolution
                    .newBuilder()
                    .setIntentKind(IntentKind.PROCEDURAL)
                    .setFunctionId("sales_by_month")
                    .setArgsJson("""{"year":2026}""")
                    .build()
            val request = GolemRequestFactory.forTurn("golem-hartland", turn(resolution = resolution), "cs")

            request.resolvedIntent.functionId shouldBe "sales_by_month"
            request.resolvedIntent.argsJson shouldBe """{"year":2026}"""
        }

        "no Resolution (the KEEP_TOGETHER path) degrades to an empty binding, not a failure" {
            val request = GolemRequestFactory.forTurn("golem-hartland", turn(), "cs")

            request.hasResolvedIntent() shouldBe false
            request.resolvedIntent.functionId shouldBe ""
        }

        "the handoff travels, and its view becomes prior_view for AMEND/DRILL" {
            val handoff =
                HandoffContext
                    .newBuilder()
                    .setSourceAgentId("golem-hartland")
                    .addEntities(EntityBinding.newBuilder().setEntityType("customer").setEntityId("c-1"))
                    .setView(
                        ViewProvenance
                            .newBuilder()
                            .setBubbleId("b-prev")
                            .setPatternId("sales_by_month")
                            .setArgsJson("""{"year":2025}""")
                            .setTotalRows(12),
                    ).build()
            val request = GolemRequestFactory.forTurn("golem-hartland", turn(handoff = handoff), "cs")

            request.context.handoff.entitiesCount shouldBe 1
            request.context.priorView.bubbleId shouldBe "b-prev"
            request.context.priorView.patternId shouldBe "sales_by_month"
            request.context.priorView.totalRows shouldBe 12L
        }

        "an empty handoff view sets no prior_view — an ordinary turn is not an amend" {
            // HandoffAssembler leaves `view` unset until the PD-4 echo carryover lands; a
            // present-but-blank view must not make golem compose as though there were
            // something to amend.
            val handoff = HandoffContext.newBuilder().setSourceAgentId("golem-hartland").build()
            val request = GolemRequestFactory.forTurn("golem-hartland", turn(handoff = handoff), "cs")

            request.context.hasPriorView() shouldBe false
        }

        "the excerpt is the last N visible turns, oldest→newest, with answer summaries" {
            val turns =
                listOf(
                    priorTurn(1, "první"),
                    priorTurn(2, "zahozená", status = TurnStatus.DISCARDED),
                    priorTurn(3, "druhá", envelopeJson = """{"text":"Tržby činily 4,2 mil."}"""),
                    priorTurn(4, "třetí", envelopeJson = "not json at all"),
                )
            val request = GolemRequestFactory.forTurn("golem-hartland", turn(), "cs", turns)

            request.context.conversationExcerptList.map { it.question } shouldContainExactly
                listOf("první", "druhá", "třetí")
            request.context.getConversationExcerpt(1).answerSummary shouldBe "Tržby činily 4,2 mil."
            // A malformed envelope loses its summary; it never fails the dispatch.
            request.context.getConversationExcerpt(2).hasAnswerSummary() shouldBe false
        }

        "the excerpt is bounded" {
            val turns = (1..12).map { priorTurn(it, "q$it") }
            val request = GolemRequestFactory.forTurn("golem-hartland", turn(), "cs", turns)

            request.context.conversationExcerptCount shouldBe GolemRequestFactory.MAX_EXCERPT_TURNS
            request.context.getConversationExcerpt(0).question shouldBe "q8"
        }
    })
