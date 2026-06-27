package org.tatrman.kantheon.pythia.handles

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.v1.Handle

/**
 * Stage 2.2 T6 — HandleTable v0: PgResultSnapshot inline cap (oversize → truncated
 * flag) and HandleRef param-binding resolution (a downstream param binds to an
 * upstream handle's projected column).
 */
class HandleTableSpec :
    StringSpec({

        val json = Json

        "a small result inlines as a PgResultSnapshot" {
            val table = HandleTable(inlineMaxBytes = 1_000)
            val rows = json.parseToJsonElement("""[{"customer_id":1},{"customer_id":2}]""") as JsonArray
            val result = table.putSnapshot("h1", rows)
            result.truncated shouldBe false
            result.handle.kindCase shouldBe Handle.KindCase.PG_SNAPSHOT
            result.handle.pgSnapshot.rowCount shouldBe 2
            table
                .get("h1")!!
                .pgSnapshot.arrowIpc.isEmpty shouldBe false
        }

        "an oversize result is flagged truncated and not inlined" {
            val table = HandleTable(inlineMaxBytes = 8)
            val rows =
                json.parseToJsonElement(
                    """[{"customer_id":1},{"customer_id":2},{"customer_id":3}]""",
                ) as JsonArray
            val result = table.putSnapshot("big", rows)
            result.truncated shouldBe true
            result.handle.pgSnapshot.truncated shouldBe true
            result.handle.pgSnapshot.arrowIpc.isEmpty shouldBe true // not inlined past the cap
        }

        "a HandleRef param binds to an upstream handle's projected column" {
            val table = HandleTable()
            table.putSnapshot(
                "H1",
                json.parseToJsonElement("""[{"customer_id":10},{"customer_id":20},{"customer_id":30}]""") as JsonArray,
            )
            val ref = """{ "${'$'}handleRef": { "handle": "H1", "projection": "customer_id" } }"""
            val params = """{ "brand": 507, "customerIds": $ref }"""
            val resolved = table.resolveBindings(params)
            // the $handleRef object is replaced with the projected id list
            val obj = json.parseToJsonElement(resolved)
            resolved.contains("[10,20,30]") shouldBe true
            obj.toString().contains("handleRef") shouldBe false
        }

        "an unknown HandleRef resolves to an empty list (not an error)" {
            val table = HandleTable()
            val params = """{ "ids": { "${'$'}handleRef": { "handle": "missing", "projection": "x" } } }"""
            table.resolveBindings(params).contains("[]") shouldBe true
        }

        "a live query handle registers without materialising" {
            val table = HandleTable()
            val handle = table.putLiveQuery("lq", "q.returns", """{"brand":412}""")
            handle.kindCase shouldBe Handle.KindCase.LIVE_QUERY
            table.get("lq")!!.liveQuery.queryRef shouldBe "q.returns"
        }
    })
