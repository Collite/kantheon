package org.tatrman.kantheon.pythia.api

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat

/**
 * proto3 ↔ JSON for the pythia/v1 wire (REST bodies, SSE frames) and the JSONB
 * persistence payloads. `print` omits insignificant whitespace; `parse` ignores
 * unknown fields (forward-compat). camelCase keys (proto3 JSON default).
 */
object ProtoJson {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()
    private val parser = JsonFormat.parser().ignoringUnknownFields()

    fun print(message: Message): String = printer.print(message)

    fun <B : Message.Builder> parseInto(
        json: String,
        builder: B,
    ): B {
        parser.merge(json, builder)
        return builder
    }
}
