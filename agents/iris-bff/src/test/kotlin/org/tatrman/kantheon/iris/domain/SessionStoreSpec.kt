package org.tatrman.kantheon.iris.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Behavioural contract for [SessionStore] (Iris Stage 1.2), exercised against the
 * in-memory fake. The Postgres binding must hold the same invariants; its
 * real-PG fidelity is covered by the integration-test suite.
 */
class SessionStoreSpec :
    StringSpec({

        fun turn(
            sessionId: java.util.UUID,
            q: String,
        ) = NewTurn(sessionId = sessionId, agentId = "golem-v2", question = q, status = TurnStatus.DONE)

        "create → get → list" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            store.getSession(s.sessionId) shouldBe s
            store.listSessions("u1").map { it.sessionId } shouldContainExactly listOf(s.sessionId)
            store.listSessions("someone-else").shouldHaveSize(0)
        }

        "appendTurn assigns a monotone seq starting at 1" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            val t1 = store.appendTurn(turn(s.sessionId, "first"))
            val t2 = store.appendTurn(turn(s.sessionId, "second"))
            t1.seq shouldBe 1
            t2.seq shouldBe 2
            store.getTurns(s.sessionId).map { it.question } shouldContainExactly listOf("first", "second")
        }

        "getTurn resolves by id; unknown id is null" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            val t = store.appendTurn(turn(s.sessionId, "q"))
            store.getTurn(t.turnId) shouldBe t
            store.getTurn(java.util.UUID.randomUUID()).shouldBeNull()
        }

        "session summary title is the first visible question" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            store.listSessions("u1").single().title shouldBe "New session"
            store.appendTurn(turn(s.sessionId, "kolik mám zákazníků?"))
            store.listSessions("u1").single().title shouldBe "kolik mám zákazníků?"
            store.listSessions("u1").single().turnCount shouldBe 1
        }

        "reset snapshots, discards all turns, and clears entity context" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            store.appendTurn(turn(s.sessionId, "a"))
            store.appendTurn(turn(s.sessionId, "b"))

            val cleared = store.reset(s.sessionId)

            cleared.entityContextJson shouldBe "[]"
            store.getTurns(s.sessionId).shouldHaveSize(0) // visible
            store.getTurns(s.sessionId, includeDiscarded = true).shouldHaveSize(2)
            store.getTurns(s.sessionId, includeDiscarded = true).all { it.status == TurnStatus.DISCARDED } shouldBe true
            store.snapshots(s.sessionId).shouldHaveSize(1)
            store.snapshots(s.sessionId).single().reason shouldBe "reset"
            store
                .snapshots(s.sessionId)
                .single()
                .turnIds
                .shouldHaveSize(2)
        }

        "discardTurnsAfter snapshots and discards only turns after the anchor" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            val t1 = store.appendTurn(turn(s.sessionId, "a"))
            store.appendTurn(turn(s.sessionId, "b"))
            store.appendTurn(turn(s.sessionId, "c"))

            val discarded = store.discardTurnsAfter(s.sessionId, t1.turnId)

            discarded.map { it.question } shouldContainExactly listOf("b", "c")
            store.getTurns(s.sessionId).map { it.question } shouldContainExactly listOf("a")
            store.snapshots(s.sessionId).single().reason shouldBe "edit_resend"
        }

        "discardTurnsAfter on the last turn is a no-op" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            store.appendTurn(turn(s.sessionId, "a"))
            val last = store.appendTurn(turn(s.sessionId, "b"))

            store.discardTurnsAfter(s.sessionId, last.turnId).shouldHaveSize(0)
            store.getTurns(s.sessionId).shouldHaveSize(2)
        }

        "appendTurn keeps seq monotone across discards (no reuse)" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            val t1 = store.appendTurn(turn(s.sessionId, "a"))
            store.appendTurn(turn(s.sessionId, "b"))
            store.discardTurnsAfter(s.sessionId, t1.turnId) // discards b (seq 2)
            val t3 = store.appendTurn(turn(s.sessionId, "c"))
            t3.seq shouldBe 3 // not 2 — discarded seqs are not reused
        }

        "restoreLatestSnapshot un-discards a reset's turns and restores entity context" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            store.appendTurn(turn(s.sessionId, "a"))
            store.appendTurn(turn(s.sessionId, "b"))
            store.reset(s.sessionId)
            store.getTurns(s.sessionId).shouldHaveSize(0) // visible after reset

            val restored = store.restoreLatestSnapshot(s.sessionId)

            restored.shouldNotBeNull()
            store.getTurns(s.sessionId).map { it.question } shouldContainExactly listOf("a", "b")
            store.getTurns(s.sessionId).all { it.status == TurnStatus.DONE } shouldBe true
            store.snapshots(s.sessionId).shouldHaveSize(0) // snapshot consumed
        }

        "restoreLatestSnapshot after edit_resend brings back the discarded tail and drops the re-run turn" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            val t1 = store.appendTurn(turn(s.sessionId, "a"))
            store.appendTurn(turn(s.sessionId, "b"))
            store.discardTurnsAfter(s.sessionId, t1.turnId) // snapshot {a,b}; discard b
            store.appendTurn(turn(s.sessionId, "b-prime")) // the edit_resend re-run

            store.restoreLatestSnapshot(s.sessionId)

            // The snapshot captured {a,b}; undo restores them and discards b-prime.
            store.getTurns(s.sessionId).map { it.question } shouldContainExactly listOf("a", "b")
        }

        "restoreLatestSnapshot walks back across successive snapshots" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            store.appendTurn(turn(s.sessionId, "a"))
            store.reset(s.sessionId) // snapshot {a}
            store.appendTurn(turn(s.sessionId, "b"))
            store.reset(s.sessionId) // snapshot {b}

            store.restoreLatestSnapshot(s.sessionId) // → {b}
            store.getTurns(s.sessionId).map { it.question } shouldContainExactly listOf("b")
            store.restoreLatestSnapshot(s.sessionId) // → {a}
            store.getTurns(s.sessionId).map { it.question } shouldContainExactly listOf("a")
        }

        "restoreLatestSnapshot is null when there is nothing to undo" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            store.restoreLatestSnapshot(s.sessionId).shouldBeNull()
        }

        "clearPendingResumeToken clears only the matching turn's token" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            store.appendTurn(
                NewTurn(
                    sessionId = s.sessionId,
                    agentId = "golem-v2",
                    question = "kdo?",
                    status = TurnStatus.CLARIFICATION,
                    pendingResumeToken = "rt-1",
                ),
            )
            store.appendTurn(turn(s.sessionId, "unrelated"))

            store.clearPendingResumeToken(s.sessionId, "rt-1")

            val tokens =
                store
                    .getTurns(s.sessionId, includeDiscarded = true)
                    .mapNotNull { it.pendingResumeToken }
            tokens.shouldHaveSize(0)
            // an unknown token is a no-op (does not throw)
            store.clearPendingResumeToken(s.sessionId, "nope")
        }

        "putV2Thread is idempotent (re-put overwrites, never throws)" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            store.putV2Thread(s.sessionId, "v2t-1")
            store.putV2Thread(s.sessionId, "v2t-1")
            store.getV2Thread(s.sessionId) shouldBe "v2t-1"
        }
    })
