package org.tatrman.kantheon.iris.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Upsert semantics of [FeedbackStore] (PD-3/PD-14). The in-memory fake backs the
 * unit/component gate; the contract it locks (esp. `corrected_agent_id` survives a
 * later verdict upsert) must hold for the Exposed store too.
 */
class FeedbackStoreSpec :
    StringSpec({

        "upsertVerdict preserves a corrected_agent_id recorded by a prior PD-14 re-ask" {
            val store = InMemoryFeedbackStore()
            val turn = UUID.randomUUID()
            // PD-14: re-ask routed to golem-hr → records a wrong_agent down-vote + correction.
            store.upsertCorrectedAgent(turn, "u1", agentId = "golem-erp", correctedAgentId = "golem-hr")
            // A later explicit 👍 must not erase the misroute correction (the routing signal).
            val after =
                store.upsertVerdict(
                    turn,
                    "u1",
                    agentId = "golem-erp",
                    verdict = "up",
                    reason = null,
                    comment = null,
                )
            after.verdict shouldBe "up"
            after.correctedAgentId shouldBe "golem-hr"
        }

        "upsert is keyed on (turn_id, user_id) — same turn, different users are independent" {
            val store = InMemoryFeedbackStore()
            val turn = UUID.randomUUID()
            store.upsertVerdict(turn, "u1", "golem-erp", "up", null, null)
            store.upsertVerdict(turn, "u2", "golem-erp", "down", "wrong_data", null)
            store.get(turn, "u1")!!.verdict shouldBe "up"
            store.get(turn, "u2")!!.verdict shouldBe "down"
            store.all().size shouldBe 2
        }
    })
