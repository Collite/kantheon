package org.tatrman.kantheon.sysifos.bff.write

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.bffbase.auth.CallerIdentity
import org.tatrman.kantheon.sysifos.bff.session.DraftScratch
import org.tatrman.kantheon.sysifos.v1.Draft
import org.tatrman.kantheon.sysifos.v1.DraftKind
import org.tatrman.kantheon.sysifos.v1.DraftStatus
import org.tatrman.kantheon.sysifos.v1.FieldValidationError
import org.tatrman.kantheon.sysifos.v1.SysifosStreamEvent

class DraftStateMachineSpec :
    StringSpec({

        val caller = CallerIdentity("u1", "acme", "tok")

        fun pendingClient(): Draft =
            Draft
                .newBuilder()
                .setDraftId("d1")
                .setKind(DraftKind.DRAFT_CLIENT)
                .setStatus(DraftStatus.DRAFT_PENDING)
                .build()

        "PENDING → COMMITTING → COMMITTED emits DraftAck then DraftCommitted" {
            runTest {
                val scratch = DraftScratch().also { it.put(pendingClient()) }
                val committer = DraftCommitter { _, _, _ -> CommitOutcome.Committed("client-123") }
                val machine = DraftStateMachine(mapOf(DraftKind.DRAFT_CLIENT to committer), scratch)

                val events = mutableListOf<SysifosStreamEvent>()
                machine.run(pendingClient(), caller) { events.add(it) }

                events[0].hasDraftAck() shouldBe true
                events[1].hasDraftCommitted() shouldBe true
                events[1].draftCommitted.artifactRef shouldBe "client-123"
                scratch.get("d1")!!.status shouldBe DraftStatus.DRAFT_COMMITTED
                scratch.get("d1")!!.commitArtifactRef shouldBe "client-123"
            }
        }

        "a Midas-core rejection emits DraftRejected with the field errors" {
            runTest {
                val scratch = DraftScratch().also { it.put(pendingClient()) }
                val err =
                    FieldValidationError
                        .newBuilder()
                        .setField("name")
                        .setCode("required")
                        .build()
                val committer = DraftCommitter { _, _, _ -> CommitOutcome.Rejected("VALIDATION_FAILED", listOf(err)) }
                val machine = DraftStateMachine(mapOf(DraftKind.DRAFT_CLIENT to committer), scratch)

                val events = mutableListOf<SysifosStreamEvent>()
                machine.run(pendingClient(), caller) { events.add(it) }

                events[0].hasDraftAck() shouldBe true
                events[1].hasDraftRejected() shouldBe true
                events[1].draftRejected.reason shouldBe "VALIDATION_FAILED"
                events[1]
                    .draftRejected.errorsList
                    .single()
                    .field shouldBe "name"
                scratch.get("d1")!!.status shouldBe DraftStatus.DRAFT_REJECTED
            }
        }

        "an unsupported draft kind rejects rather than throwing" {
            runTest {
                val draft =
                    Draft
                        .newBuilder()
                        .setDraftId("d2")
                        .setKind(DraftKind.DRAFT_PORTFOLIO)
                        .build()
                val scratch = DraftScratch().also { it.put(draft) }
                val machine = DraftStateMachine(emptyMap(), scratch)

                val events = mutableListOf<SysifosStreamEvent>()
                machine.run(draft, caller) { events.add(it) }

                events[1].hasDraftRejected() shouldBe true
                events[1].draftRejected.reason shouldBe "UNSUPPORTED_DRAFT_KIND"
            }
        }
    })
