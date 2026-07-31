package org.tatrman.kantheon.iris.api

import com.google.protobuf.util.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.protocol.assemble.ProtocolAssembler
import org.tatrman.kantheon.iris.protocol.render.MarkdownRenderer
import org.tatrman.kantheon.iris.protocol.sections.TurnFacts
import org.tatrman.kantheon.protocol.v1.Scope
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private val protocolLog = LoggerFactory.getLogger("org.tatrman.kantheon.iris.api.ProtocolRoutes")

/**
 * `/protocol` (contracts §3.1). Parse → authorize → delegate → serialize; no
 * business logic lives here.
 *
 * **Degradation is never an error status.** A document assembled with half its
 * sources down is still a 200 — the degradation is inside the document and named
 * in its receipts (P-4). The only failures are the caller's: unknown session,
 * non-member, unparseable scope.
 */
fun Route.protocolRoutes(
    store: SessionStore,
    auth: BearerAuthenticator,
    assembler: ProtocolAssembler,
    renderer: MarkdownRenderer = MarkdownRenderer(),
) {
    val printer = JsonFormat.printer().omittingInsignificantWhitespace()
    val json = Json { ignoreUnknownKeys = true }

    route("/v1") {
        post("/session/{sessionId}/protocol") {
            val caller = call.requireCaller(auth) ?: return@post

            val sessionId =
                call.parameters["sessionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("invalid_session_id", "sessionId must be a UUID"),
                    )

            // Authorization resolves BEFORE the body is parsed, so a caller cannot
            // distinguish "your scope was malformed" from "that session exists".
            //
            // And "not yours" answers 404, not 403 (contracts amendment A-6). A 403
            // says *this session exists and you may not have it*, which turns the
            // endpoint into an existence oracle for any session id a caller can
            // guess or overhear. Every other iris-bff session route already collapses
            // the two cases — `ChatRoutes.ownedSession` — and a debug surface is not
            // the place to start leaking what the primary surfaces withhold.
            val session = store.getSession(sessionId)
            if (session == null || session.userId != caller.userId) {
                return@post call.respond(
                    HttpStatusCode.NotFound,
                    ErrorBody("session_not_found", "No such session"),
                )
            }

            val raw = runCatching { json.parseToJsonElement(call.receiveText()) }.getOrNull()
            val scope =
                (raw as? JsonObject)?.get("scope")?.let(::parseScope)
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("invalid_scope", """scope must be "last", "session" or {"lastN": <positive int>}"""),
                    )

            // The visible turns, in order. `seq` is re-derived from position rather
            // than taken from the row: the store already excludes discarded turns, so
            // the stored seq has gaps and a reader counting "Turn 3" would be confused
            // by a document that jumps from 2 to 5.
            val turns =
                store.getTurns(sessionId).mapIndexed { index, t ->
                    TurnFacts(
                        turnId = t.turnId.toString(),
                        seq = index + 1,
                        question = t.question,
                        agentId = t.agentId,
                        status = t.status.wire,
                        origin = t.origin.name.lowercase(),
                        startedAt = OffsetDateTime.ofInstant(t.createdAt, ZoneOffset.UTC).toString(),
                        // Duration is not stored per turn; the header section reports 0
                        // rather than inventing one. Timings ride the record's hints.
                        durationMs = 0,
                        routingOutcome = if (t.alternatesOffered.isEmpty()) "routed" else "needs_user_pick",
                        userId = session.userId,
                        tenantId = session.tenantId,
                    )
                }

            val doc =
                assembler.assemble(
                    ProtocolAssembler.Request(
                        sessionId = sessionId,
                        scope = scope,
                        turns = turns,
                        bearer = caller.bearer,
                        turnCountTotal = turns.size,
                        sessionCreatedAt = OffsetDateTime.ofInstant(session.createdAt, ZoneOffset.UTC).toString(),
                    ),
                )

            val envelope =
                FormatEnvelope
                    .newBuilder()
                    .setBubbleId(doc.protocolId)
                    .setThreadId(sessionId.toString())
                    .setText(renderer.render(doc))
                    .setFormat(FormatSpec.newBuilder().setKind(FormatKind.MARKDOWN))
                    .setCreatedAt(doc.generatedAt)
                    .setAgentVersion("iris-bff/protocol")
                    .build()

            protocolLog.info(
                "protocol generated: session={} scope={} turns={}",
                sessionId,
                scope.kindCase,
                doc.turnsCount,
            )

            call.respondText(
                buildJsonObject {
                    put("protocolId", JsonPrimitive(doc.protocolId))
                    put("title", JsonPrimitive(doc.header.title))
                    put("envelope", json.parseToJsonElement(printer.print(envelope)))
                }.toString(),
                ContentType.Application.Json,
            )
        }
    }
}

/**
 * `"last"` | `"session"` | `{"lastN": <positive int>}` → the proto oneof.
 * Anything else is null, which the route turns into a 400 — including `lastN: 0`
 * and negatives, which are not "a small scope" but a caller mistake.
 */
internal fun parseScope(element: JsonElement): Scope? {
    (element as? JsonPrimitive)?.let { p ->
        if (!p.isString) return null
        return when (p.content) {
            "last" -> Scope.newBuilder().setLastTurn(true).build()
            "session" -> Scope.newBuilder().setWholeSession(true).build()
            else -> null
        }
    }
    val n = (element as? JsonObject)?.get("lastN")?.jsonPrimitive?.intOrNull ?: return null
    return if (n > 0) Scope.newBuilder().setLastN(n).build() else null
}
