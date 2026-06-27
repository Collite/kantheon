package org.tatrman.kantheon.iris.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.tatrman.kantheon.iris.domain.SessionRecord
import org.tatrman.kantheon.iris.domain.SessionSummary
import org.tatrman.kantheon.iris.domain.SessionWithTurns
import org.tatrman.kantheon.iris.domain.TurnRecord

// Proto-shape JSON, proto-canonical camelCase (consistent with the envelope/v1
// wire decision, Stage 1.1). These map iris/v1 Session / TurnPointer onto the
// REST surface (contracts §2.1).

private val passthroughJson = Json { ignoreUnknownKeys = true }

private fun jsonOrNull(raw: String?): JsonElement? =
    raw?.let { runCatching { passthroughJson.parseToJsonElement(it) }.getOrNull() }

@Serializable
data class ErrorBody(
    val errorCode: String,
    val message: String,
)

@Serializable
data class TurnPointerDto(
    val turnId: String,
    val agentId: String,
    val question: String?,
    val artifactRef: String?,
    val displayedBlockIds: List<String>,
    val status: String,
    val origin: String,
    val createdAt: String,
)

@Serializable
data class SessionChipDto(
    val display: String,
    val prompt: String,
    val source: String = "static",
)

@Serializable
data class SessionDto(
    val sessionId: String,
    val userId: String,
    val tenantId: String,
    val entityContext: JsonElement?,
    val turns: List<TurnPointerDto>,
    val createdAt: String,
    val updatedAt: String,
    // Discovery surface (Stage 2.2 BFF-grow): mirrored from golem /v2/session at
    // session creation so the FE chip strip / example questions / version render
    // without a direct golem call. Empty when golem was unreachable (best-effort).
    val staticChips: List<SessionChipDto> = emptyList(),
    val exampleQuestions: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val agentVersion: String = "",
)

/** Neutral discovery holder passed into [toDto] — no v2-wire leak into the DTO surface. */
data class SessionDiscovery(
    val staticChips: List<SessionChipDto> = emptyList(),
    val exampleQuestions: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val agentVersion: String = "",
)

@Serializable
data class RefreshResultDto(
    val service: String,
    val status: String,
    val detail: String? = null,
    val version: String? = null,
)

@Serializable
data class RefreshResponseDto(
    val results: List<RefreshResultDto>,
)

@Serializable
data class SessionSummaryDto(
    val sessionId: String,
    val title: String,
    val turnCount: Int,
    val updatedAt: String,
)

@Serializable
data class TurnEnvelopeDto(
    val turnId: String,
    val agentId: String,
    val envelope: JsonElement?,
    val status: String,
)

fun TurnRecord.toPointerDto() =
    TurnPointerDto(
        turnId = turnId.toString(),
        agentId = agentId,
        question = question,
        artifactRef = artifactRef,
        displayedBlockIds = displayedBlockIds,
        status = status.wire,
        origin = origin.wire,
        createdAt = createdAt.toString(),
    )

fun SessionRecord.toDto(
    turns: List<TurnRecord> = emptyList(),
    discovery: SessionDiscovery = SessionDiscovery(),
) = SessionDto(
    sessionId = sessionId.toString(),
    userId = userId,
    tenantId = tenantId,
    entityContext = jsonOrNull(entityContextJson),
    turns = turns.map { it.toPointerDto() },
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    staticChips = discovery.staticChips,
    exampleQuestions = discovery.exampleQuestions,
    packages = discovery.packages,
    agentVersion = discovery.agentVersion,
)

fun SessionWithTurns.toDto() = session.toDto(turns)

fun SessionSummary.toDto() =
    SessionSummaryDto(
        sessionId = sessionId.toString(),
        title = title,
        turnCount = turnCount,
        updatedAt = updatedAt.toString(),
    )

fun TurnRecord.toEnvelopeDto() =
    TurnEnvelopeDto(
        turnId = turnId.toString(),
        agentId = agentId,
        envelope = jsonOrNull(envelopeJson),
        status = status.wire,
    )
