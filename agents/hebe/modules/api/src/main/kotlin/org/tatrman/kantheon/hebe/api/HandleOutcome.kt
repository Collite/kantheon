package org.tatrman.kantheon.hebe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface HandleOutcome {
    @Serializable
    @SerialName("done")
    data class Done(
        val reply: OutboundMessage,
    ) : HandleOutcome

    @Serializable
    @SerialName("pending")
    data class Pending(
        val reason: PendingReason,
    ) : HandleOutcome

    @Serializable
    @SerialName("no_response")
    data class NoResponse(
        val cause: String,
    ) : HandleOutcome

    @Serializable
    @SerialName("failed")
    data class Failed(
        val message: String,
    ) : HandleOutcome
}

@Serializable
sealed interface PendingReason {
    @Serializable
    @SerialName("approval")
    data class Approval(
        val request: ApprovalRequest,
    ) : PendingReason

    @Serializable
    @SerialName("auth_entry")
    data class AuthEntry(
        val purpose: String,
    ) : PendingReason
}
