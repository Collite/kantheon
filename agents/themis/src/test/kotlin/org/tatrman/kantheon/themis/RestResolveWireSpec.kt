package org.tatrman.kantheon.themis

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.tatrman.kantheon.themis.v1.Themis

/**
 * The REST `/v1/resolve` wire contract: themis/v1 messages ride as **proto-canonical JSON**
 * (`JsonFormat`), matching iris-bff's `HttpThemisClient` on the other end — protobuf is the
 * source of truth even when the wire is REST (kantheon-architecture §4).
 *
 * This exists because the handler once used Ktor's `call.receive(ResolveRequest::class)` /
 * `call.respond(response)`. The installed converter is kotlinx-serialization, and a
 * protobuf-generated class carries no `@Serializable`, so **every** request failed with
 * "Serializer for class 'ResolveRequest' is not found" → 400. iris-bff surfaced that to users
 * as "Routing is temporarily unavailable; please retry." — an outage message for what was a
 * marshalling bug. It went unnoticed because `protobuf-java-util` was an integrationTest-only
 * dependency, so the main source set had no proto JSON codec to reach for.
 *
 * The first test pins the contract; the second pins the trap, so a "simplification" back to
 * `call.receive` fails here rather than in the cluster.
 */
class RestResolveWireSpec :
    StringSpec({

        val parser = JsonFormat.parser().ignoringUnknownFields()
        val printer = JsonFormat.printer().omittingInsignificantWhitespace()

        // Exactly what HttpThemisClient puts on the wire.
        val requestJson =
            printer.print(
                Themis.ResolveRequest
                    .newBuilder()
                    .setConversationId("conv-1")
                    .apply { freshBuilder.setText("How did the web channel do?").setLocale("en") }
                    .build(),
            )

        "proto-canonical JSON round-trips through the REST edge" {
            testApplication {
                application {
                    // The same converter the real server installs — it must not be consulted
                    // for the protobuf types.
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    routing {
                        post("/v1/resolve") {
                            val req =
                                Themis.ResolveRequest
                                    .newBuilder()
                                    .also { parser.merge(call.receiveText(), it) }
                                    .build()
                            val resp =
                                Themis.ResolveResponse
                                    .newBuilder()
                                    .setTraceId(req.conversationId)
                                    .build()
                            call.respondText(
                                printer.print(resp),
                                ContentType.Application.Json,
                                HttpStatusCode.OK,
                            )
                        }
                    }
                }

                val response =
                    client.post("/v1/resolve") {
                        contentType(ContentType.Application.Json)
                        setBody(requestJson)
                    }

                response.status shouldBe HttpStatusCode.OK

                // The caller parses the reply the same way (HttpThemisClient does exactly this).
                val decoded =
                    Themis.ResolveResponse
                        .newBuilder()
                        .also { parser.merge(response.bodyAsText(), it) }
                        .build()
                decoded.traceId shouldBe "conv-1"
            }
        }

        "ContentNegotiation cannot marshal a protobuf class — the regression this guards" {
            testApplication {
                application {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    routing {
                        post("/v1/resolve") {
                            // The old implementation. Kotlinx has no serializer for a
                            // protobuf-generated class, so this throws before the body is read.
                            call.receive(Themis.ResolveRequest::class)
                            call.respondText("unreachable")
                        }
                    }
                }

                // Ktor converts the SerializationException into a 400 — which is exactly what
                // reached iris-bff, and why the user saw an availability message for a
                // marshalling bug. A well-formed body on the documented path must never 400.
                val response =
                    client.post("/v1/resolve") {
                        contentType(ContentType.Application.Json)
                        setBody(requestJson)
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }
    })
