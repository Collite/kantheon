package org.tatrman.kantheon.hebe.api

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

interface Channel {
    val name: String

    suspend fun start(scope: CoroutineScope): Flow<IncomingMessage>

    suspend fun reply(
        ctx: ReplyContext,
        msg: OutboundMessage,
    )

    fun supportsDraftUpdates(): Boolean = false

    suspend fun updateDraft(
        ctx: ReplyContext,
        partial: String,
    ) {}

    suspend fun broadcast(
        userId: String,
        msg: OutboundMessage,
    ) {}

    suspend fun healthCheck(): ChannelHealth

    suspend fun shutdown()
}

@Serializable
data class IncomingMessage(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    val channel: String,
    val userId: String,
    val senderId: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val threadId: ExternalThreadId? = null,
    val metadata: JsonObject,
    val receivedAt: kotlin.time.Instant,
    val isInternal: Boolean = false,
    val isAgentBroadcast: Boolean = false,
    val triggeringMissionId: String? = null,
    val sessionId: String? = null,
)

@Serializable
sealed interface ExternalThreadId {
    val raw: String

    @Serializable
    @SerialName("trusted")
    data class Trusted(
        override val raw: String,
    ) : ExternalThreadId

    @Serializable
    @SerialName("untrusted")
    data class Untrusted(
        override val raw: String,
    ) : ExternalThreadId
}

@Serializable
data class ReplyContext(
    @Serializable(with = UUIDSerializer::class) val incomingId: UUID,
    val sessionId: String,
    val threadId: ExternalThreadId? = null,
    val routingTargets: List<String> = emptyList(),
)

@Serializable
data class OutboundMessage(
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val approvalRequest: ApprovalRequest? = null,
)

@Serializable
data class Attachment(
    val mime: String,
    val bytes: ByteArray,
    val name: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Attachment
        if (mime != other.mime) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (name != other.name) return false
        return true
    }

    override fun hashCode(): Int {
        var result = mime.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}

@Serializable
data class ApprovalRequest(
    val id: String,
    val tool: String,
    val argsRedacted: JsonObject,
    val expiresAt: kotlin.time.Instant,
)

@Serializable
enum class ChannelHealth {
    Up,
    Degraded,
    Down,
}
