package org.tatrman.kantheon.iris.protocol.record

import com.google.protobuf.util.JsonFormat
import org.tatrman.kantheon.protocol.v1.RecordCaptures
import org.tatrman.kantheon.protocol.v1.RecordPointers

/**
 * JSONB codec for the two `iris_protocol_records` document columns
 * (contracts §6.1). Proto `JsonFormat`, not a hand-rolled shape — the wire
 * policy (kantheon-architecture §4) holds for stored JSON too, so the column
 * contents are exactly the canonical proto JSON of [RecordPointers] and
 * [RecordCaptures]: **camelCase keys**, `bytes` fields as base64 strings
 * (`resolveResponse` / `securityApplied`).
 *
 * Reads use `ignoringUnknownFields()`. That is the forward-tolerance half of
 * the [SchemaVersion] policy: a record written by a newer minor must still be
 * readable by an older assembler, degraded but not broken. Rejecting unknown
 * keys would turn every additive change into an estate-wide outage of
 * `/protocol` for old rows.
 */
internal object ProtocolRecordJson {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()
    private val parser = JsonFormat.parser().ignoringUnknownFields()

    fun pointersToJson(pointers: RecordPointers): String = printer.print(pointers)

    fun capturesToJson(captures: RecordCaptures): String = printer.print(captures)

    fun pointersFromJson(json: String): RecordPointers =
        RecordPointers.newBuilder().also { parser.merge(json, it) }.build()

    fun capturesFromJson(json: String): RecordCaptures =
        RecordCaptures.newBuilder().also { parser.merge(json, it) }.build()
}
