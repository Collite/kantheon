package org.tatrman.kallimachos.adapters

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.adapters.relational.InMemoryRelationalAdapter
import org.tatrman.kallimachos.adapters.relational.NewPage
import org.tatrman.kallimachos.adapters.relational.NewPart
import org.tatrman.kallimachos.adapters.relational.NewSource

/**
 * P1 Stage 1.2 T2 — the relational port contract (id allocation, insert,
 * retrieval-by-id) exercised against the in-memory adapter. The live Exposed/PG
 * adapter is verified in the integration suite (kantheon "Exposed stores
 * integration-deferred"); this spec is the mocked-unit gate for the port.
 */
class RelationalAdapterSpec :
    StringSpec({
        "ids are DB-generated and globally unique across sources + parts + pages" {
            val a = InMemoryRelationalAdapter()
            val s = a.insertSource(NewSource(title = "Doc"))
            val parts = a.insertParts(s.id, listOf(NewPart(0, "paragraph", "p0"), NewPart(1, "paragraph", "p1")))
            val pages = a.insertPages(listOf(NewPage("ENTITY", "E", "# E")))

            val allIds = listOf(s.id) + parts.map { it.id } + pages.map { it.id }
            allIds.toSet().size shouldBe allIds.size // all distinct (one shared sequence)
            allIds shouldContainExactly listOf(1L, 2L, 3L, 4L) // monotone across families
        }

        "source + part insert round-trips by id" {
            val a = InMemoryRelationalAdapter()
            val s = a.insertSource(NewSource(title = "Doc", mimeType = "text/plain"))
            val parts = a.insertParts(s.id, listOf(NewPart(0, "paragraph", "hello"), NewPart(1, "paragraph", "world")))

            a.getSource(s.id)!!.title shouldBe "Doc"
            a.getPart(parts[0].id)!!.contentText shouldBe "hello"
            a.partsOfSource(s.id).map { it.idx } shouldContainExactly listOf(0, 1)
        }

        "missing ids return null" {
            val a = InMemoryRelationalAdapter()
            a.getSource(999).shouldBeNull()
            a.getPart(999).shouldBeNull()
            a.getPage(999).shouldBeNull()
        }
    })
