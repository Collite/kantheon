package org.tatrman.kantheon.golem.execution

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.envelope.v1.CurrentView
import org.tatrman.kantheon.golem.persistence.GolemTurnRecord
import org.tatrman.kantheon.golem.persistence.GolemTurnStatus
import org.tatrman.kantheon.golem.persistence.InMemoryTurnsRepository
import org.tatrman.kantheon.golem.v1.GolemContext
import java.time.Instant
import java.util.UUID

private fun priorTurn(bubbleId: String): GolemTurnRecord =
    GolemTurnRecord(
        id = UUID.randomUUID(),
        requestId = UUID.randomUUID(),
        golemId = "golem-erp",
        userId = "u1",
        tenantId = "default",
        question = "q",
        resolvedIntentJson = "{}",
        planJson = """{"source":"PATTERN","confidence":0.9}""",
        envelopesJson = "[]",
        currentViewJson = """{"bubbleId":"$bubbleId","patternId":"acct.balance"}""",
        status = GolemTurnStatus.DONE,
        createdAt = Instant.parse("2026-06-19T10:00:00Z"),
    )

private fun contextWithPriorView(builder: CurrentView.Builder.() -> Unit): GolemContext =
    GolemContext.newBuilder().setPriorView(CurrentView.newBuilder().apply(builder)).build()

class PriorViewResolverSpec :
    StringSpec({

        "AMEND resolves the prior view and reads the producing turn's plan back" {
            val repo = InMemoryTurnsRepository()
            val prior = priorTurn("b-1")
            repo.insert(prior)
            val ctx =
                contextWithPriorView {
                    bubbleId = "b-1"
                    patternId = "acct.balance"
                    argsJson = """{"period":"2026-06"}"""
                }

            val resolved = PriorViewResolver(repo).resolve(ctx, "u1", "default")!!
            resolved.patternId shouldBe "acct.balance"
            resolved.argsJson shouldBe """{"period":"2026-06"}"""
            resolved.bubbleId shouldBe "b-1"
            resolved.sourceTurnId shouldBe prior.id
            resolved.priorPlanJson shouldBe """{"source":"PATTERN","confidence":0.9}"""
        }

        "AMEND for another tenant's bubble doesn't read its plan (H2 — scoped lookup)" {
            val repo = InMemoryTurnsRepository()
            repo.insert(priorTurn("b-1")) // owned by u1/default
            val resolved =
                PriorViewResolver(repo).resolve(
                    contextWithPriorView {
                        bubbleId = "b-1"
                        patternId = "acct.balance"
                    },
                    userId = "u1",
                    tenantId = "other", // different tenant — the producing turn must not be found
                )!!
            resolved.sourceTurnId.shouldBeNull()
            resolved.priorPlanJson.shouldBeNull()
        }

        "DRILL resolves the prior view's sql even when no producing turn is found" {
            val resolved =
                PriorViewResolver(InMemoryTurnsRepository()).resolve(
                    contextWithPriorView {
                        bubbleId = "unknown"
                        sql = "SELECT * FROM invoices"
                    },
                    userId = "u1",
                    tenantId = "default",
                )!!
            resolved.sql shouldBe "SELECT * FROM invoices"
            resolved.sourceTurnId.shouldBeNull()
            resolved.priorPlanJson.shouldBeNull()
        }

        "a turn with no prior view resolves to null" {
            PriorViewResolver(InMemoryTurnsRepository())
                .resolve(GolemContext.getDefaultInstance(), "u1", "default")
                .shouldBeNull()
        }
    })
