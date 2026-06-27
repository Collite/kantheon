package org.tatrman.kantheon.iris.action

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.domain.SessionRecord
import org.tatrman.kantheon.iris.domain.TurnRecord
import org.tatrman.kantheon.iris.domain.TurnStatus
import java.time.Instant
import java.util.UUID

private fun session() =
    SessionRecord(
        sessionId = UUID.randomUUID(),
        userId = "u1",
        tenantId = "t1",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

private fun turn() =
    TurnRecord(
        turnId = UUID.randomUUID(),
        sessionId = UUID.randomUUID(),
        seq = 1,
        agentId = "golem-erp",
        question = "kolik tržeb?",
        status = TurnStatus.DONE,
        artifactRef = "artifact-7",
        createdAt = Instant.EPOCH,
    )

class EscalationHandlerSpec :
    StringSpec({

        "buildChip carries the handoff anchored on the turn + proposed question + label" {
            val handler = EscalationHandler(InMemoryAuditStore(Ed25519Signer()))
            val chip = handler.buildChip(session(), turn(), "why did revenue drop?")

            chip.label shouldBe "Investigate this"
            chip.proposedQuestion shouldBe "why did revenue drop?"
            chip.handoff.sourceAgentId shouldBe "golem-erp"
            chip.handoff.sourceTurnRef shouldBe "artifact-7"
            chip.handoff.userQuestion shouldBe "kolik tržeb?"
            chip.handoff.suggestedFocus shouldBe "why did revenue drop?"
        }

        "recordEscalation writes an escalation audit row naming pythia as the hint" {
            val audit = InMemoryAuditStore(Ed25519Signer())
            val handler = EscalationHandler(audit)
            val t = turn()
            handler.recordEscalation("u1", t, "investigate this")

            val row = audit.all().single { it.eventKind == "escalation" }
            row.payloadJson shouldContain "pythia"
            row.payloadJson shouldContain t.turnId.toString()
        }
    })
