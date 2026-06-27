package org.tatrman.kantheon.midas.loaders.excel.mapper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.midas.loaders.excel.fixtures.BrokerFixtures
import org.tatrman.kantheon.midas.loaders.excel.parser.BrokerRegistry
import org.tatrman.kantheon.midas.loaders.excel.parser.ExcelParser
import org.tatrman.kantheon.midas.v1.TransactionKind
import org.tatrman.kantheon.midas.v1.TransactionSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Stage 1.5 T3/T4 — parsed rows map to midas `Transaction` drafts: kind vocabulary,
 * ISO + day-first date parsing, decimals, currency, `<broker>:<ref>` external_id,
 * `TX_SRC_LOADER_EXCEL`, and per-row error handling (unknown kind / bad date).
 */
class TransactionMapperSpec :
    StringSpec({

        val registry = BrokerRegistry.load()
        val parser = ExcelParser()
        val mapper = TransactionMapper()
        val portfolio = "11111111-1111-1111-1111-111111111111"

        fun draftsFor(broker: String): List<DraftRow> {
            val template = registry.get(broker)
            val bytes = if (broker == "alpha") BrokerFixtures.alphaBytes() else BrokerFixtures.betaBytes()
            return mapper.map(parser.parse(bytes.inputStream(), template), template, portfolio)
        }

        fun epochOf(date: String): Long =
            LocalDate
                .parse(date)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .epochSecond

        "alpha BUY maps to a TX_BUY draft with quantity/price/fee/total + external_id" {
            val buy = draftsFor("alpha").first()
            buy.error.shouldBeNull()
            buy.symbol shouldBe "AAPL"
            val tx = buy.draft
            tx.portfolioId shouldBe portfolio
            tx.kind shouldBe TransactionKind.TX_BUY
            tx.source shouldBe TransactionSource.TX_SRC_LOADER_EXCEL
            BigDecimal(tx.quantity).compareTo(BigDecimal("10")) shouldBe 0
            BigDecimal(tx.price.amount).compareTo(BigDecimal("150.50")) shouldBe 0
            tx.price.currency shouldBe "USD"
            BigDecimal(tx.fee.amount).compareTo(BigDecimal("1.00")) shouldBe 0
            BigDecimal(tx.total.amount).compareTo(BigDecimal("1506.00")) shouldBe 0
            tx.externalId shouldBe "alpha:A1001"
            tx.tradeDate.seconds shouldBe epochOf("2026-01-15")
            tx.hasSettleDate() shouldBe true
        }

        "alpha DIV maps to TX_DIVIDEND" {
            draftsFor("alpha")[2].draft.kind shouldBe TransactionKind.TX_DIVIDEND
        }

        "beta uses day-first dates and its own vocabulary (Buy/Interest)" {
            val drafts = draftsFor("beta")
            val buy = drafts.first()
            buy.draft.kind shouldBe TransactionKind.TX_BUY
            buy.draft.currency shouldBe "EUR"
            buy.draft.externalId shouldBe "beta:B5001"
            buy.draft.tradeDate.seconds shouldBe
                Instant
                    .ofEpochSecond(
                        LocalDate.of(2026, 3, 15).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                    ).epochSecond

            drafts.last().draft.kind shouldBe TransactionKind.TX_INTEREST
        }

        "an unknown transaction type becomes a per-row error, not a thrown exception" {
            val template = registry.get("alpha")
            val rows = parser.parse(BrokerFixtures.alphaBytes().inputStream(), template)
            val tampered = rows.first().copy(values = rows.first().values + ("kind" to "WAT"))
            val result = mapper.mapRow(tampered, template, portfolio)
            val err = result.error.shouldNotBeNull()
            err.contains("unknown transaction type") shouldBe true
        }

        "a date that doesn't match the template format is a per-row error" {
            val template = registry.get("alpha")
            val rows = parser.parse(BrokerFixtures.alphaBytes().inputStream(), template)
            val tampered = rows.first().copy(values = rows.first().values + ("trade_date" to "15/01/2026"))
            mapper.mapRow(tampered, template, portfolio).error.shouldNotBeNull()
        }
    })
