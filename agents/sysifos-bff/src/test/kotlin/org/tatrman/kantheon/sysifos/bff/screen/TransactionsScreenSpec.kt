package org.tatrman.kantheon.sysifos.bff.screen

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TransactionsScreenSpec :
    StringSpec({

        val assets = """{"assets":[{"assetId":"a-1","symbol":"AAPL"}]}"""
        val portfolios = """{"portfolios":[{"portfolioId":"p-1","name":"Growth"}]}"""

        "nests a derived cash leg under its security leg by correlationId" {
            val tx =
                """
                {"transactions":[
                  {"transactionId":"t-1","kind":"TX_BUY","source":"TX_SRC_MANUAL","correlationId":"corr-1"},
                  {"transactionId":"t-2","kind":"TX_CASH_DEBIT","source":"TX_SRC_DERIVATION","correlationId":"corr-1"}
                ],"pageInfo":{"page":0,"size":50,"total":1}}
                """.trimIndent()

            val out = Json.parseToJsonElement(assembleTransactionsScreen(tx, assets, portfolios)).jsonObject
            val rows = out["transactions"]!!.jsonArray
            rows shouldHaveSize 1
            val security = rows[0].jsonObject
            security["transactionId"]!!.jsonPrimitive.content shouldBe "t-1"
            val cashLegs = security["cashLegs"]!!.jsonArray
            cashLegs shouldHaveSize 1
            cashLegs[0].jsonObject["transactionId"]!!.jsonPrimitive.content shouldBe "t-2"
            // The screen carries the dictionaries the FE needs, no second round-trip.
            out["assets"]!!.jsonArray shouldHaveSize 1
            out["portfolios"]!!.jsonArray shouldHaveSize 1
            out["pageInfo"]!!.jsonObject["total"]!!.jsonPrimitive.content shouldBe "1"
        }

        "a security leg with no cash leg gets an empty cashLegs array" {
            val tx = """{"transactions":[{"transactionId":"t-1","kind":"TX_BUY","source":"TX_SRC_MANUAL"}]}"""
            val out = Json.parseToJsonElement(assembleTransactionsScreen(tx, assets, portfolios)).jsonObject
            out["transactions"]!!.jsonArray[0].jsonObject["cashLegs"]!!.jsonArray shouldBe JsonArray(emptyList())
        }

        "a cash leg whose security leg is off-page stays top-level" {
            val tx =
                """{"transactions":[{"transactionId":"t-2","kind":"TX_CASH_DEBIT","source":"TX_SRC_DERIVATION","correlationId":"corr-9"}]}"""
            val out = Json.parseToJsonElement(assembleTransactionsScreen(tx, assets, portfolios)).jsonObject
            out["transactions"]!!.jsonArray shouldHaveSize 1
        }

        "two security legs sharing a correlationId keep their cash legs top-level (not mis-nested)" {
            // reverse+replace edit: original + reversal share corr-1 (contracts §2.4).
            val tx =
                """
                {"transactions":[
                  {"transactionId":"t-1","kind":"TX_BUY","source":"TX_SRC_MANUAL","correlationId":"corr-1"},
                  {"transactionId":"t-1r","kind":"TX_BUY","source":"TX_SRC_REVERSAL","correlationId":"corr-1"},
                  {"transactionId":"c-1","kind":"TX_CASH_DEBIT","source":"TX_SRC_DERIVATION","correlationId":"corr-1"},
                  {"transactionId":"c-1r","kind":"TX_CASH_CREDIT","source":"TX_SRC_DERIVATION","correlationId":"corr-1"}
                ]}
                """.trimIndent()

            val out = Json.parseToJsonElement(assembleTransactionsScreen(tx, assets, portfolios)).jsonObject
            val rows = out["transactions"]!!.jsonArray
            // Both security legs render with no nested cash, and both cash legs stay top-level
            // → 2 security + 2 cash = 4 top-level rows; no cash leg dumped on the wrong leg.
            rows shouldHaveSize 4
            val securityLegs = rows.filter { it.jsonObject["cashLegs"] != null }
            securityLegs shouldHaveSize 2
            securityLegs.forEach { it.jsonObject["cashLegs"]!!.jsonArray shouldHaveSize 0 }
        }
    })
