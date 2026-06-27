package org.tatrman.kantheon.hebe.cli

import org.tatrman.kantheon.hebe.channels.ChannelManagerImpl
import org.tatrman.kantheon.hebe.channels.telegram.TelegramChannel
import org.tatrman.kantheon.hebe.channels.telegram.TelegramWebhookRoute
import org.tatrman.kantheon.hebe.channels.web.Routes
import org.tatrman.kantheon.hebe.channels.web.WebChannel
import org.tatrman.kantheon.hebe.gateway.Gateway
import org.tatrman.kantheon.hebe.gateway.MemoryRoutes
import org.tatrman.kantheon.hebe.gateway.ReceiptsRoutes
import java.nio.file.Path

/**
 * Wires channel modules into the Gateway.
 *
 * Call [applyToGateway] inside the `configureRoutes` lambda passed to [Gateway.start].
 * Call [registerChannels] on the [ChannelManagerImpl] before starting it.
 */
class ChannelWiring(
    private val webChannel: WebChannel,
    private val telegramWebhookRoute: TelegramWebhookRoute? = null,
    private val memoryStore: org.tatrman.kantheon.hebe.api.MemoryStore? = null,
    private val receiptsDir: Path? = null,
) {
    fun applyToGateway(routing: io.ktor.server.routing.Routing) {
        Routes.register(routing, webChannel)
        telegramWebhookRoute?.register(routing)
        memoryStore?.let { MemoryRoutes.register(routing, it) }
        receiptsDir?.let { ReceiptsRoutes.register(routing, it) }
    }

    suspend fun registerChannels(
        manager: ChannelManagerImpl,
        telegramChannel: TelegramChannel? = null,
    ) {
        manager.register(webChannel)
        telegramChannel?.let { manager.register(it) }
    }
}
