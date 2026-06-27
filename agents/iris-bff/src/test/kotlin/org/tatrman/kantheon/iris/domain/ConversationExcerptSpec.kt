package org.tatrman.kantheon.iris.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly

class ConversationExcerptSpec :
    StringSpec({

        "builds the last N visible turns, oldest→newest, excluding discarded" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            listOf("a", "b", "c", "d").forEach {
                store.appendTurn(
                    NewTurn(sessionId = s.sessionId, agentId = "golem-v2", question = it, status = TurnStatus.DONE),
                )
            }
            val all = store.getTurns(s.sessionId, includeDiscarded = true)

            val excerpt = ConversationExcerptBuilder.build(all, maxTurns = 2)
            excerpt.turns.map { it.question } shouldContainExactly listOf("c", "d")
        }

        "discarded turns are excluded from the excerpt" {
            val store = InMemorySessionStore()
            val s = store.createSession("u1", "t1")
            val first =
                store.appendTurn(
                    NewTurn(sessionId = s.sessionId, agentId = "golem-v2", question = "keep", status = TurnStatus.DONE),
                )
            store.appendTurn(
                NewTurn(sessionId = s.sessionId, agentId = "golem-v2", question = "drop", status = TurnStatus.DONE),
            )
            store.discardTurnsAfter(s.sessionId, first.turnId)

            val excerpt =
                ConversationExcerptBuilder.build(
                    store.getTurns(s.sessionId, includeDiscarded = true),
                    maxTurns = 6,
                )
            excerpt.turns.map { it.question } shouldContainExactly listOf("keep")
        }
    })
