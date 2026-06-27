package org.tatrman.kantheon.midas.loaders.excel.parser

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Stage 1.5 T2 — the two broker templates load from the `brokers/` classpath dir
 * with their sheet/header/date config and kind vocabulary.
 */
class BrokerRegistrySpec :
    StringSpec({

        "loads the alpha + beta broker templates" {
            BrokerRegistry.load().brokerIds() shouldContainExactlyInAnyOrder listOf("alpha", "beta")
        }

        "alpha template carries its sheet, header row, date format, and kind map" {
            val alpha = BrokerRegistry.load().get("alpha")
            alpha.sheet shouldBe "Transactions"
            alpha.headerRow shouldBe 1
            alpha.dateFormat shouldBe "yyyy-MM-dd"
            alpha.columns shouldContain (BrokerTemplate.Fields.EXTERNAL_ID to "Reference")
            alpha.kindMap shouldContain ("DIV" to "TX_DIVIDEND")
        }

        "beta template has a non-default header row and day-first dates" {
            val beta = BrokerRegistry.load().get("beta")
            beta.sheet shouldBe "Activity"
            beta.headerRow shouldBe 3
            beta.dateFormat shouldBe "dd/MM/yyyy"
            beta.kindMap shouldContain ("Interest" to "TX_INTEREST")
        }

        "an unknown broker_id throws UnknownBrokerException" {
            shouldThrow<UnknownBrokerException> { BrokerRegistry.load().get("nope") }
        }
    })
