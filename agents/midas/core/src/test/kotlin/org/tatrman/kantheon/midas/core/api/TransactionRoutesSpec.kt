package org.tatrman.kantheon.midas.core.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
import io.ktor.client.request.request
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.mockk.every
import io.mockk.mockk
import org.tatrman.kantheon.midas.core.auth.BearerAuthenticator
import org.tatrman.kantheon.midas.core.repository.InsertedLegs
import org.tatrman.kantheon.midas.core.repository.ReverseResult
import org.tatrman.kantheon.midas.core.repository.TransactionRepository
import org.tatrman.kantheon.midas.v1.BalanceEntryPreview
import org.tatrman.kantheon.midas.v1.Transaction
import org.tatrman.kantheon.midas.v1.TransactionKind
import java.util.Base64

/**
 * Stage 1.3 T6 — transaction route behaviour (mocked repository): the §1.1.A cash
 * leg surfaces as a Rule-6 message, edits return reversal+replacement, balance
 * preview round-trips. Real DB derivation is proven by the deploy smoke.
 */
class TransactionRoutesSpec :
    StringSpec({

        val tenant = "11111111-1111-1111-1111-111111111111"

        fun bearer(): String {
            val payload =
                Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("""{"sub":"u1","tenant":"$tenant","exp":9999999999}""".toByteArray())
            return "Bearer h.$payload.s"
        }

        fun Application.mount(repo: TransactionRepository) {
            installMidasErrorPages()
            routing { route("/api/v1") { transactionRoutes(repo, BearerAuthenticator()) } }
        }

        "POST surfaces the derived cash leg as a Rule-6 message" {
            val repo = mockk<TransactionRepository>()
            val security =
                Transaction
                    .newBuilder()
                    .setTransactionId("sec")
                    .setKind(TransactionKind.TX_BUY)
                    .build()
            val cash =
                Transaction
                    .newBuilder()
                    .setTransactionId("cash")
                    .setKind(TransactionKind.TX_CASH_DEBIT)
                    .setCorrelationId("corr-1")
                    .build()
            every { repo.insert(eq(tenant), any(), any()) } returns InsertedLegs(security, cash)

            io.ktor.server.testing.testApplication {
                application { mount(repo) }
                val res =
                    client.post("/api/v1/transactions") {
                        header("Authorization", bearer())
                        header("X-Tenant-Id", tenant)
                        setBody("""{"transaction":{"kind":"TX_BUY","currency":"USD"}}""")
                    }
                res.status shouldBe HttpStatusCode.Created
                res.bodyAsText() shouldContain "cash_leg_derived"
            }
        }

        "PATCH returns reversal + replacement" {
            val repo = mockk<TransactionRepository>()
            val reversal = Transaction.newBuilder().setTransactionId("rev").build()
            val replacement = Transaction.newBuilder().setTransactionId("new").build()
            every { repo.reverseAndReplace(eq(tenant), any(), any(), any(), any()) } returns
                ReverseResult(reversal, replacement)

            io.ktor.server.testing.testApplication {
                application { mount(repo) }
                val res =
                    client.request("/api/v1/transactions/${java.util.UUID.randomUUID()}") {
                        method = io.ktor.http.HttpMethod.Patch
                        header("Authorization", bearer())
                        header("X-Tenant-Id", tenant)
                        setBody("""{"reason":"fix"}""")
                    }
                res.status shouldBe HttpStatusCode.OK
                res.bodyAsText() shouldContain "rev"
                res.bodyAsText() shouldContain "new"
            }
        }

        "DELETE returns the reversal entry" {
            val repo = mockk<TransactionRepository>()
            every { repo.reverseAndReplace(eq(tenant), any(), any(), isNull(), any()) } returns
                ReverseResult(Transaction.newBuilder().setTransactionId("rev").build(), null)

            io.ktor.server.testing.testApplication {
                application { mount(repo) }
                val res =
                    client.delete("/api/v1/transactions/${java.util.UUID.randomUUID()}?reason=x") {
                        header("Authorization", bearer())
                        header("X-Tenant-Id", tenant)
                    }
                res.status shouldBe HttpStatusCode.OK
                res.bodyAsText() shouldContain "rev"
            }
        }

        "balance-entry preview round-trips the proposed adjustment" {
            val repo = mockk<TransactionRepository>()
            every { repo.previewBalance(eq(tenant), any()) } returns
                BalanceEntryPreview
                    .newBuilder()
                    .setCurrentQuantity("10")
                    .setTargetQuantity("25")
                    .setDiffQuantity("15")
                    .build()

            io.ktor.server.testing.testApplication {
                application { mount(repo) }
                val res =
                    client.post("/api/v1/balance-entries:preview") {
                        header("Authorization", bearer())
                        header("X-Tenant-Id", tenant)
                        setBody("""{"portfolio_id":"p","asset_id":"a","target_quantity":"25"}""")
                    }
                res.status shouldBe HttpStatusCode.OK
                res.bodyAsText() shouldContain "\"diffQuantity\":\"15\""
            }
        }
    })
