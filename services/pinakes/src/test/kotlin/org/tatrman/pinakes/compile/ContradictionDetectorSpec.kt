package org.tatrman.pinakes.compile

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.kallimachos.v1.EdgeKind
import org.tatrman.kallimachos.v1.PageKind
import org.tatrman.pinakes.clients.PrometheusClient
import org.tatrman.pinakes.resolve.ResolveOutcome
import org.tatrman.pinakes.resolve.ResolvedPage

/**
 * P3 Stage 3.3 T1 — the contradiction-flag pass. Pages asserting conflicting
 * facts about one entity produce a `CONTRADICTS` edge; a malformed/failed
 * detection yields none (parse-safe).
 */
class ContradictionDetectorSpec :
    StringSpec({
        fun page(
            localId: Int,
            content: String,
        ) = ResolvedPage(PageDraft(localId, PageKind.ENTITY, "Kaufland", content, listOf(1)), ResolveOutcome.NEW)

        fun client(reply: String) =
            object : PrometheusClient {
                override suspend fun complete(
                    systemPrompt: String,
                    userPrompt: String,
                ): String = reply
            }

        val pages = listOf(page(0, "Kaufland is German."), page(1, "Kaufland is French."))

        "conflicting pages produce a CONTRADICTS edge" {
            val edges = runBlocking { ContradictionDetector(client("""[{"from":0,"to":1}]""")).detect(pages) }
            edges.size shouldBe 1
            edges.first().kind shouldBe EdgeKind.CONTRADICTS
            edges.first().fromLocalId shouldBe 0
            edges.first().toLocalId shouldBe 1
        }

        "no contradictions → no edges" {
            runBlocking { ContradictionDetector(client("[]")).detect(pages) }.size shouldBe 0
        }

        "a malformed detection response yields no edges (parse-safe)" {
            runBlocking { ContradictionDetector(client("garbage")).detect(pages) }.size shouldBe 0
        }
    })
