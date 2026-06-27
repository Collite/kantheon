package org.tatrman.kantheon.midas.core.infra

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import shared.libs.db.common.DatabaseConnection

/**
 * Stage 1.4 — the MV refresh calls the SECURITY DEFINER bypass function and fails
 * open (warn-and-continue) so a missing function/MV never breaks a write. The real
 * insert → refresh → MV-populated path is proven by the deploy smoke.
 */
class MvRefresherSpec :
    StringSpec({

        "refresh issues REFRESH MATERIALIZED VIEW (run as the BYPASSRLS owner)" {
            MvRefresher.REFRESH_SQL shouldBe "REFRESH MATERIALIZED VIEW mv_position_current"
        }

        "refreshPositions fails open when the DB call throws" {
            val db = mockk<DatabaseConnection>()
            every { db.query<Any?>(any()) } throws
                RuntimeException("relation mv_position_current does not exist")
            shouldNotThrowAny { MvRefresher(db).refreshPositions() }
        }
    })
