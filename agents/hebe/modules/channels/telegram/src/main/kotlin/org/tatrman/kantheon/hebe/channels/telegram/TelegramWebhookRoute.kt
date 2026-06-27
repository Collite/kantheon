package org.tatrman.kantheon.hebe.channels.telegram

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.objects.Update

class TelegramWebhookRoute(
    private val secretPath: String,
    private val updateConsumer: TelegramUpdateConsumer,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper =
        ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules()

    @Suppress("TooGenericExceptionCaught")
    fun register(routing: Route) {
        routing.post("/api/webhooks/telegram/$secretPath") {
            try {
                val body = call.receiveText()
                val update = objectMapper.readValue(body, Update::class.java)
                logger.debug("received webhook update: {}", update.updateId)
                updateConsumer.consume(listOf(update))
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                logger.error("failed to process webhook update", e)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }
}
