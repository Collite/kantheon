package org.tatrman.kantheon.iris.protocol.record

import com.google.protobuf.ByteString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.protocol.v1.ProtocolHints
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import org.tatrman.kantheon.protocol.v1.RecordCaptures
import org.tatrman.kantheon.protocol.v1.RecordPointers
import java.util.Base64
import java.util.UUID

/**
 * The JSONB column shapes of contracts §6.1 — asserted on the *parsed JSON*,
 * not on a string, so key order is irrelevant but key **names** are pinned.
 * These are the shape a future assembler (and any operator running a `psql`
 * query against `iris_protocol_records`) depends on.
 */
class RecordSerializationSpec :
    StringSpec({

        val json = Json { ignoreUnknownKeys = true }

        "pointers serialize to the JSONB JSON shape of contracts §1 RecordPointers (camelCase wire)" {
            val pointers =
                RecordPointers
                    .newBuilder()
                    .setTraceId("0af7651916cd43dd8448eb211c80319c")
                    .setCorrelationId("corr-9")
                    .setGatewayTurnRef("turn-9")
                    .addPlanIds("plan-1")
                    .addPlanIds("plan-2")
                    .addLlmCallRefs("gw-771")
                    .setSqlInline("SELECT 1")
                    .setLogWindowFrom("2026-07-30T09:00:00+02:00")
                    .setLogWindowTo("2026-07-30T09:00:05+02:00")
                    .setHints(ProtocolHints.newBuilder().addPlanIds("plan-1").setSqlRef("sql-1"))
                    .build()

            val obj = json.parseToJsonElement(ProtocolRecordJson.pointersToJson(pointers)).jsonObject

            // camelCase, per canonical proto JSON — NOT the proto's snake_case.
            obj.keys shouldContainExactlyInAnyOrder
                setOf(
                    "traceId",
                    "correlationId",
                    "gatewayTurnRef",
                    "planIds",
                    "llmCallRefs",
                    "sqlInline",
                    "logWindowFrom",
                    "logWindowTo",
                    "hints",
                )
            obj["traceId"]!!.jsonPrimitive.content shouldBe "0af7651916cd43dd8448eb211c80319c"
            obj["planIds"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("plan-1", "plan-2")
            obj["hints"]!!.jsonObject["sqlRef"]!!.jsonPrimitive.content shouldBe "sql-1"

            // Unset fields are omitted, not written as nulls — `sqlRef` is the
            // untaken half of the sql_ref/sql_inline pair.
            obj.containsKey("sqlRef") shouldBe false

            ProtocolRecordJson.pointersFromJson(ProtocolRecordJson.pointersToJson(pointers)) shouldBe pointers
        }

        "captures serialize as b64 proto bytes under resolveResponse/securityApplied keys" {
            val f2 = "themis-resolve-response-bytes".toByteArray()
            val f7 = "validate-security-applied-bytes".toByteArray()
            val captures =
                RecordCaptures
                    .newBuilder()
                    .setResolveResponse(ByteString.copyFrom(f2))
                    .setSecurityApplied(ByteString.copyFrom(f7))
                    .build()

            val obj = json.parseToJsonElement(ProtocolRecordJson.capturesToJson(captures)).jsonObject

            obj.keys shouldContainExactlyInAnyOrder setOf("resolveResponse", "securityApplied")
            obj["resolveResponse"]!!.jsonPrimitive.content shouldBe Base64.getEncoder().encodeToString(f2)
            obj["securityApplied"]!!.jsonPrimitive.content shouldBe Base64.getEncoder().encodeToString(f7)

            ProtocolRecordJson.capturesFromJson(ProtocolRecordJson.capturesToJson(captures)) shouldBe captures

            // The DDL default for an F7-less turn.
            ProtocolRecordJson.capturesToJson(RecordCaptures.getDefaultInstance()) shouldBe "{}"
            ProtocolRecordJson.capturesFromJson("{}") shouldBe RecordCaptures.getDefaultInstance()
        }

        "every written record is stamped schema_version = CURRENT" {
            val store = InMemoryProtocolRecordStore()

            // Unstamped, and stamped with a stale version — both come back CURRENT.
            listOf("", "protocol/v0.9").forEach { claimed ->
                val turnId = UUID.randomUUID()
                store.write(
                    ProtocolRecord
                        .newBuilder()
                        .setTurnId(turnId.toString())
                        .setSchemaVersion(claimed)
                        .build(),
                )
                store.readByTurnId(turnId)!!.schemaVersion shouldBe SchemaVersion.CURRENT
            }

            SchemaVersion.CURRENT shouldBe "protocol/v1.0"
            SchemaVersion.stamp(ProtocolRecord.getDefaultInstance()).schemaVersion shouldBe SchemaVersion.CURRENT
        }

        "a record with unknown future keys in pointers JSON still parses (forward-tolerant)" {
            // Written by a hypothetical protocol/v1.1 that added `mcpCallRefs`.
            val future =
                """
                {"traceId":"trace-1","mcpCallRefs":["mcp-1"],"hints":{"sqlRef":"s1","futureHint":42},"unknownBlock":{"a":1}}
                """.trimIndent()

            val parsed = ProtocolRecordJson.pointersFromJson(future)

            parsed.traceId shouldBe "trace-1"
            parsed.hints.sqlRef shouldBe "s1"

            // Forward-tolerant, but honest about it: JSON parsing DROPS the unknown
            // keys (unlike the binary wire, which retains them as unknown fields).
            // An older assembler therefore reads a degraded record, never a broken
            // one — and must not be relied on to round-trip a newer minor back to
            // storage without loss. Bumping the minor is what signals the gap.
            json
                .parseToJsonElement(
                    ProtocolRecordJson.pointersToJson(parsed),
                ).jsonObject
                .containsKey("mcpCallRefs") shouldBe
                false

            // Empty and absent both parse to the default instance rather than throwing.
            ProtocolRecordJson.pointersFromJson("{}") shouldBe RecordPointers.getDefaultInstance()
            (json.parseToJsonElement("{}") as JsonObject).size shouldBe 0
        }
    })
