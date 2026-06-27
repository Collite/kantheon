package org.tatrman.kantheon.iris.api

import kotlinx.serialization.Serializable

/** `POST /v1/action` body (contracts §1 `TypedActionRequest`, §2.4). Proto-canonical camelCase. */
@Serializable
data class TypedActionRequestDto(
    val sessionId: String,
    val bubbleId: String? = null,
    val action: TypedActionDto,
)

/** A typed action (contracts §1 `TypedAction`): `kind` discriminator + Rule-7 `payloadJson`. */
@Serializable
data class TypedActionDto(
    val kind: String,
    val payloadJson: String,
)

/** `edit_resend` payload schema (contracts §2.4). */
@Serializable
data class EditResendPayload(
    val editedQuestion: String,
    val fromTurnId: String,
)

/** `reask_agent` payload schema (contracts §2.4, PD-14). */
@Serializable
data class ReaskPayload(
    val turnId: String,
    val targetAgentId: String,
)

/** `investigate` payload (PD-1): escalate a turn to Pythia. */
@Serializable
data class InvestigatePayload(
    val turnId: String,
    val proposedQuestion: String? = null,
)

/** `chip_invocation` payload (contracts §2.4): re-submit a chip as a normal turn. */
@Serializable
data class ChipInvocationPayload(
    val prompt: String,
    val patternId: String? = null,
)
