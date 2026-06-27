package org.tatrman.kantheon.iris.routing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.kantheon.iris.domain.SessionRecord
import org.tatrman.kantheon.iris.domain.TurnRecord
import org.tatrman.kantheon.iris.domain.TurnStatus
import java.time.Instant
import java.util.UUID

private fun session(entityContextJson: String = "[]"): SessionRecord =
    SessionRecord(
        sessionId = UUID.randomUUID(),
        userId = "u1",
        tenantId = "t1",
        entityContextJson = entityContextJson,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

private fun turn(
    agentId: String = "golem-erp",
    question: String = "kolik tržeb?",
    artifactRef: String? = "artifact-1",
): TurnRecord =
    TurnRecord(
        turnId = UUID.randomUUID(),
        sessionId = UUID.randomUUID(),
        seq = 1,
        agentId = agentId,
        question = question,
        status = TurnStatus.DONE,
        artifactRef = artifactRef,
        createdAt = Instant.EPOCH,
    )

class HandoffAssemblerSpec :
    StringSpec({

        "first turn (no previous) → null handoff" {
            HandoffAssembler.fromPreviousTurn(session(), previousTurn = null).shouldBeNull()
        }

        "maps source agent, turn ref and question from the previous turn" {
            val h = HandoffAssembler.fromPreviousTurn(session(), turn())!!
            h.sourceAgentId shouldBe "golem-erp"
            h.sourceTurnRef shouldBe "artifact-1"
            h.userQuestion shouldBe "kolik tržeb?"
        }

        "absent artifact ref maps to empty source_turn_ref (not null)" {
            val h = HandoffAssembler.fromPreviousTurn(session(), turn(artifactRef = null))!!
            h.sourceTurnRef shouldBe ""
        }

        "parses session entity context (snake_case) into bindings" {
            val ctx =
                """[{"entity_type":"customer","entity_id":"c-9","display_label":"Kaufland ČR v.o.s.","source":"user_pick"}]"""
            val h = HandoffAssembler.fromPreviousTurn(session(ctx), turn())!!
            h.entitiesCount shouldBe 1
            h.getEntities(0).entityType shouldBe "customer"
            h.getEntities(0).entityId shouldBe "c-9"
            h.getEntities(0).displayLabel shouldBe "Kaufland ČR v.o.s."
            h.getEntities(0).source shouldBe "user_pick"
        }

        "malformed entity context does not throw — yields no bindings" {
            val h = HandoffAssembler.fromPreviousTurn(session("not json at all"), turn())!!
            h.entitiesCount shouldBe 0
        }

        "entities are capped at 50, most-relevant first preserved" {
            val many =
                (1..80).joinToString(prefix = "[", postfix = "]", separator = ",") {
                    """{"entity_type":"product","entity_id":"p-$it","display_label":"P$it"}"""
                }
            val h = HandoffAssembler.fromPreviousTurn(session(many), turn())!!
            h.entitiesCount shouldBe HandoffAssembler.MAX_ENTITIES
            h.getEntities(0).entityId shouldBe "p-1"
        }

        "suggested_focus is truncated at 1 KiB" {
            val focus = "x".repeat(5000)
            val h = HandoffAssembler.fromPreviousTurn(session(), turn(), suggestedFocus = focus)!!
            h.suggestedFocus.toByteArray(Charsets.UTF_8).size shouldBe HandoffAssembler.MAX_FOCUS_BYTES
        }

        "empty suggested_focus is left unset" {
            val h = HandoffAssembler.fromPreviousTurn(session(), turn())!!
            h.suggestedFocus shouldBe ""
        }

        "a handoff is produced for any non-first turn" {
            HandoffAssembler.fromPreviousTurn(session(), turn()) shouldNotBe null
        }
    })
