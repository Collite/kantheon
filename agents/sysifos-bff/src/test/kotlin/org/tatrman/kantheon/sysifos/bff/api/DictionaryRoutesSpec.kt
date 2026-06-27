package org.tatrman.kantheon.sysifos.bff.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tatrman.kantheon.sysifos.bff.dictionaries.Cached
import org.tatrman.kantheon.sysifos.bff.module
import org.tatrman.kantheon.sysifos.bff.testDeps

class DictionaryRoutesSpec :
    StringSpec({

        "all four dictionary endpoints answer with labelled entries" {
            testApplication {
                application { module(testDeps()) }

                val currencies = client.get("/dictionaries/currencies")
                currencies.status shouldBe HttpStatusCode.OK
                currencies.bodyAsText() shouldContain "CZK"

                val txKinds = client.get("/dictionaries/transaction-kinds")
                txKinds.status shouldBe HttpStatusCode.OK
                // tx kinds carry cs/en labels and include the read-only derived cash legs.
                txKinds.bodyAsText() shouldContain "TX_CASH_CREDIT"

                client.get("/dictionaries/asset-kinds").bodyAsText() shouldContain "ASSET_STOCK"
                client.get("/dictionaries/brokers").bodyAsText() shouldContain "Fio"
            }
        }

        "Cached serves a hit within the TTL and recomputes after expiry" {
            var calls = 0
            var clock = 0L
            val cached = Cached(ttlMs = 100, now = { clock }) { ++calls }
            cached.get() shouldBe 1
            cached.get() shouldBe 1 // still fresh → no recompute
            cached.isFresh() shouldBe true
            clock = 200 // past the TTL
            cached.isFresh() shouldBe false
            cached.get() shouldBe 2 // recomputed
            calls shouldBe 2
        }
    })
