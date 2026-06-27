package org.tatrman.kantheon.hebe.channels.telegram

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TelegramWebhookRouteTest {
    private val secretPath = "super-secret-token-42"

    private fun makeConsumer() = mockk<TelegramUpdateConsumer>(relaxed = true)

    @Test
    fun `valid update is parsed and forwarded to consumer`() =
        testApplication {
            val consumer = makeConsumer()
            application {
                routing {
                    TelegramWebhookRoute(secretPath, consumer).register(this)
                }
            }

            val updateJson =
                """
                {
                  "update_id": 12345,
                  "message": {
                    "message_id": 1,
                    "date": 1700000000,
                    "chat": { "id": 999, "type": "private" },
                    "from": { "id": 123456789, "first_name": "Test", "is_bot": false },
                    "text": "hello"
                  }
                }
                """.trimIndent()

            val response =
                client.post("/api/webhooks/telegram/$secretPath") {
                    contentType(ContentType.Application.Json)
                    setBody(updateJson)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) { consumer.consume(match { it.size == 1 && it[0].updateId == 12345 }) }
        }

    @Test
    fun `malformed JSON returns 500`() =
        testApplication {
            val consumer = makeConsumer()
            application {
                routing {
                    TelegramWebhookRoute(secretPath, consumer).register(this)
                }
            }

            val response =
                client.post("/api/webhooks/telegram/$secretPath") {
                    contentType(ContentType.Application.Json)
                    setBody("not-valid-json")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            verify(exactly = 0) { consumer.consume(any()) }
        }

    @Test
    fun `POST to wrong secret path returns 404`() =
        testApplication {
            val consumer = makeConsumer()
            application {
                routing {
                    TelegramWebhookRoute(secretPath, consumer).register(this)
                }
            }

            val response =
                client.post("/api/webhooks/telegram/wrong-path") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"update_id":1}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            verify(exactly = 0) { consumer.consume(any()) }
        }
}
