package org.tatrman.kantheon.sysifos.bff.api

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

/** FE↔BFF wire is canonical proto JSON of the `sysifos/v1` types (contracts §3). */
private val protoPrinter: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()

/** Respond with a proto message rendered as canonical proto JSON. */
suspend fun ApplicationCall.respondProto(
    message: Message,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(protoPrinter.print(message), ContentType.Application.Json, status)
