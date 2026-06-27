package org.tatrman.kantheon.sysifos.bff.write

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.sysifos.v1.DraftKind

class WriteDispatcherSpec :
    StringSpec({

        "single-record draft kinds route to the SYNC proxy path" {
            listOf(
                DraftKind.DRAFT_CLIENT,
                DraftKind.DRAFT_PORTFOLIO,
                DraftKind.DRAFT_TRANSACTION,
                DraftKind.DRAFT_BALANCE_ENTRY,
                DraftKind.DRAFT_ASSET,
                DraftKind.DRAFT_RECONCILIATION_DECISION,
            ).forEach { WriteDispatcher.route(it) shouldBe WritePath.SYNC }
        }

        "bulk + import draft kinds route to the ASYNC draft path" {
            WriteDispatcher.route(DraftKind.DRAFT_TRANSACTION_BATCH) shouldBe WritePath.ASYNC
            WriteDispatcher.route(DraftKind.DRAFT_LOADER_RUN_COMMIT) shouldBe WritePath.ASYNC
        }
    })
