package org.tatrman.kantheon.iris.inbox

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun inv(
    id: String,
    status: String,
    turn: String? = null,
    caller: String = "IRIS",
) = InvestigationSummary(id, "why?", status, "t0", "t1", caller, turn)

class InboxAggregatorSpec :
    StringSpec({

        "maps all 12 Pythia states to the 5 user-facing buckets" {
            val cases =
                listOf(
                    "SUBMITTED" to UserFacingStatus.RUNNING,
                    "RESOLVING" to UserFacingStatus.RUNNING,
                    "PLANNING" to UserFacingStatus.RUNNING,
                    "EXECUTING" to UserFacingStatus.RUNNING,
                    "AWAITING_CLARIFICATION" to UserFacingStatus.NEEDS_INPUT,
                    "AWAITING_ENTITY_CHOICE" to UserFacingStatus.NEEDS_INPUT,
                    "AWAITING_INTENT_CHOICE" to UserFacingStatus.NEEDS_INPUT,
                    "AWAITING_PARAM_FILL" to UserFacingStatus.NEEDS_INPUT,
                    "AWAITING_BUDGET_DECISION" to UserFacingStatus.NEEDS_INPUT,
                    "DONE" to UserFacingStatus.DONE,
                    "FAILED" to UserFacingStatus.FAILED,
                    "HALTED" to UserFacingStatus.CANCELLED,
                )
            cases.forEach { (raw, expected) -> UserFacingStatus.of(raw) shouldBe expected }
        }

        "joins the originating turn (session/turn/origin) and counts running + needs-input" {
            val invs =
                listOf(
                    inv("i1", "EXECUTING", turn = "turn-1"),
                    inv("i2", "AWAITING_BUDGET_DECISION", turn = "turn-2"),
                    inv("i3", "DONE", turn = "turn-3"),
                )
            val joins =
                mapOf(
                    "turn-1" to TurnJoin("sess-1", "Revenue dip?", "turn-1", "user"),
                    "turn-2" to TurnJoin("sess-2", "Margin?", "turn-2", "scheduled"),
                )
            val view = InboxAggregator.build(invs) { joins[it] }

            view.counts.running shouldBe 1
            view.counts.needsInput shouldBe 1
            val i1 = view.items.first { it.investigationId == "i1" }
            i1.status shouldBe UserFacingStatus.RUNNING
            i1.sessionId shouldBe "sess-1"
            i1.turnId shouldBe "turn-1"
            i1.origin shouldBe "user"
            view.items.first { it.investigationId == "i2" }.origin shouldBe "scheduled"
        }

        "an orphaned investigation (no matching turn) still renders, origin from caller kind" {
            val view =
                InboxAggregator.build(
                    listOf(inv("i9", "EXECUTING", turn = "gone", caller = "SCHEDULED")),
                ) { null }
            val item = view.items.single()
            item.sessionId shouldBe null
            item.origin shouldBe "scheduled" // inferred from caller.kind
        }

        "HALTED renders a partial (cancel-with-partials)" {
            val view = InboxAggregator.build(listOf(inv("i1", "HALTED"))) { null }
            view.items.single().let {
                it.status shouldBe UserFacingStatus.CANCELLED
                it.partial shouldBe true
            }
        }
    })
