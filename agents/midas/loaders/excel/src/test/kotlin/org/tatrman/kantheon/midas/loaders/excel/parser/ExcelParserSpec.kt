package org.tatrman.kantheon.midas.loaders.excel.parser

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.midas.loaders.excel.fixtures.BrokerFixtures

/**
 * Stage 1.5 T1/T2 — the POI parser reads each fixture broker template cleanly:
 * correct row count, header resolution (incl. beta's preamble + row-3 header), and
 * normalised cell values (numbers without trailing `.0`).
 */
class ExcelParserSpec :
    StringSpec({

        val registry = BrokerRegistry.load()
        val parser = ExcelParser()

        "parses the alpha fixture into 4 rows with mapped fields" {
            val rows = parser.parse(BrokerFixtures.alphaBytes().inputStream(), registry.get("alpha"))
            rows.size shouldBe BrokerFixtures.ALPHA_ROW_COUNT

            val first = rows.first()
            first[BrokerTemplate.Fields.KIND] shouldBe "BUY"
            first[BrokerTemplate.Fields.SYMBOL] shouldBe "AAPL"
            first[BrokerTemplate.Fields.TRADE_DATE] shouldBe "2026-01-15"
            first[BrokerTemplate.Fields.QUANTITY] shouldBe "10" // numeric cell, no trailing .0
            first[BrokerTemplate.Fields.PRICE] shouldBe "150.5"
            first[BrokerTemplate.Fields.CURRENCY] shouldBe "USD"
            first[BrokerTemplate.Fields.EXTERNAL_ID] shouldBe "A1001"
        }

        "parses the beta fixture below its 2-row preamble (header on row 3)" {
            val rows = parser.parse(BrokerFixtures.betaBytes().inputStream(), registry.get("beta"))
            rows.size shouldBe BrokerFixtures.BETA_ROW_COUNT

            val first = rows.first()
            first[BrokerTemplate.Fields.KIND] shouldBe "Buy"
            first[BrokerTemplate.Fields.SYMBOL] shouldBe "MSFT"
            first[BrokerTemplate.Fields.TRADE_DATE] shouldBe "15/03/2026"
            // sourceRowIndex is the 0-based sheet row: preamble(0,1) + header(2) + first data(3).
            first.sourceRowIndex shouldBe 3

            // The interest row has no ticker → blank symbol, still parsed.
            rows.last()[BrokerTemplate.Fields.KIND] shouldBe "Interest"
            rows.last()[BrokerTemplate.Fields.SYMBOL] shouldBe ""
        }

        "the committed fixture files on the classpath parse (guards the deploy-smoke samples)" {
            for ((broker, resource) in listOf(
                "alpha" to "/fixtures/alpha_sample.xlsx",
                "beta" to "/fixtures/beta_sample.xlsx",
            )) {
                val stream =
                    requireNotNull(javaClass.getResourceAsStream(resource)) { "missing committed fixture $resource" }
                parser.parse(stream, registry.get(broker)).size shouldBe 4
            }
        }

        "a missing sheet raises ExcelParseException" {
            val wrong = registry.get("alpha").copy(sheet = "DoesNotExist")
            shouldThrow<ExcelParseException> { parser.parse(BrokerFixtures.alphaBytes().inputStream(), wrong) }
        }

        "a missing mapped column raises ExcelParseException" {
            val wrong =
                registry.get("alpha").copy(
                    columns =
                        registry.get("alpha").columns + ("kind" to "Nonexistent Header"),
                )
            shouldThrow<ExcelParseException> { parser.parse(BrokerFixtures.alphaBytes().inputStream(), wrong) }
        }
    })
