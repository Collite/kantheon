package org.tatrman.kantheon.iris.api

import kotlinx.serialization.Serializable

/** `POST /v1/chat/{stream,turn}` body (contracts §2.2). Proto-canonical camelCase. */
@Serializable
data class ChatTurnRequestDto(
    val sessionId: String,
    val question: String,
    val desiredFormat: String? = null,
    // RoutingPickChip click re-issue (Stage 3.1 T5): pins Layer-0 routing in Themis.
    val routingHintAgentId: String? = null,
    // The UI's selected language, per turn. Agents render in this locale — Golem picks its
    // prompt bundle by it (`prompts/<locale>/intent.yaml`), so it decides the language of
    // captions and follow-up chips, not just of the SPA's own strings. Absent/blank falls
    // back to `iris.locale`. It is per-turn rather than per-session because the picker can
    // change mid-conversation without minting a new session.
    val locale: String? = null,
)

/** `POST /v1/chat/resume` body (contracts §2.2). */
@Serializable
data class ChatResumeRequestDto(
    val sessionId: String,
    val resumeToken: String,
    val selectedOptionId: String? = null,
    val freeTextAnswer: String? = null,
)
