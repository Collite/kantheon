package org.tatrman.kantheon.golem.execution

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.golem.persistence.GolemTurnRecord
import org.tatrman.kantheon.golem.persistence.GolemTurnStatus
import org.tatrman.kantheon.golem.persistence.InMemoryTurnsRepository
import org.tatrman.kantheon.golem.v1.RowSelection
import java.time.Instant
import java.util.UUID

private fun turnWithRows(
    bubbleId: String,
    rows: List<Map<String, Any>>,
): GolemTurnRecord {
    // The displayed rows live in the envelope's `contentJson` (itself a JSON-array string),
    // and the turn is found by bubble via `currentViewJson.bubbleId`.
    val rowsJson =
        buildJsonArray {
            rows.forEach { r ->
                add(
                    buildJsonObject {
                        r.forEach { (k, v) ->
                            when (v) {
                                is Int -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                    },
                )
            }
        }.toString()
    val envelopesJson =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("bubbleId", bubbleId)
                    put("contentJson", rowsJson)
                },
            )
        }.toString()
    val currentViewJson = buildJsonObject { put("bubbleId", bubbleId) }.toString()
    return GolemTurnRecord(
        id = UUID.randomUUID(),
        requestId = UUID.randomUUID(),
        golemId = "golem-erp",
        userId = "u1",
        tenantId = "t1",
        question = "q",
        resolvedIntentJson = "{}",
        planJson = "{}",
        envelopesJson = envelopesJson,
        currentViewJson = currentViewJson,
        status = GolemTurnStatus.DONE,
        createdAt = Instant.now(),
    )
}

class SelectionResolverSpec :
    StringSpec({

        "resolves a row-detail selection into selected rows + the first-row selection context" {
            val turns = InMemoryTurnsRepository()
            turns.insert(
                turnWithRows(
                    "b1",
                    listOf(
                        mapOf("KOD_STR" to "DF01", "NAZEV" to "Praha"),
                        mapOf("KOD_STR" to "DF02", "NAZEV" to "Brno"),
                    ),
                ),
            )
            val resolver = SelectionResolver(turns)

            val resolved =
                resolver.resolve(
                    RowSelection
                        .newBuilder()
                        .setBubbleId("b1")
                        .addRowIndices(1)
                        .build(),
                    userId = "u1",
                    tenantId = "t1",
                )
            resolved.shouldNotBeNull()
            resolved.selectedRows.size shouldBe 1
            resolved.selectionContext shouldBe
                JsonObject(mapOf("KOD_STR" to JsonPrimitive("DF02"), "NAZEV" to JsonPrimitive("Brno")))
        }

        "a selection for another tenant's bubble resolves to null (H2 — no cross-tenant read)" {
            val turns = InMemoryTurnsRepository()
            turns.insert(turnWithRows("b1", listOf(mapOf("KOD_STR" to "DF01")))) // owned by u1/t1
            SelectionResolver(turns)
                .resolve(
                    RowSelection
                        .newBuilder()
                        .setBubbleId("b1")
                        .addRowIndices(0)
                        .build(),
                    userId = "u1",
                    tenantId = "t2", // different tenant — must not see u1/t1's rows
                ).shouldBeNull()
        }

        "a stale bubble (no such turn) resolves to null — the turn proceeds without a selection" {
            val resolver = SelectionResolver(InMemoryTurnsRepository())
            resolver
                .resolve(
                    RowSelection
                        .newBuilder()
                        .setBubbleId("missing")
                        .addRowIndices(0)
                        .build(),
                    userId = "u1",
                    tenantId = "t1",
                ).shouldBeNull()
        }

        "an out-of-range index resolves to null" {
            val turns = InMemoryTurnsRepository()
            turns.insert(turnWithRows("b1", listOf(mapOf("KOD_STR" to "DF01"))))
            SelectionResolver(turns)
                .resolve(
                    RowSelection
                        .newBuilder()
                        .setBubbleId("b1")
                        .addRowIndices(5)
                        .build(),
                    userId = "u1",
                    tenantId = "t1",
                ).shouldBeNull()
        }

        "a null selection (and a NONE resolver) resolves to null" {
            SelectionResolver(InMemoryTurnsRepository()).resolve(null, "u1", "t1").shouldBeNull()
            SelectionResolver.NONE
                .resolve(
                    RowSelection
                        .newBuilder()
                        .setBubbleId("b1")
                        .addRowIndices(0)
                        .build(),
                    userId = "u1",
                    tenantId = "t1",
                ).shouldBeNull()
        }
    })
