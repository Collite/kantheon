package org.tatrman.kantheon.iris.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.tatrman.kantheon.iris.domain.ArtifactRecord

/**
 * `POST /v1/artifacts` body (contracts §2.8). A pin captures a turn's bubble
 * (`kind = pin`, `turnId` + `bubbleId` + `name`); a dashboard names an ordered
 * pin collection (`kind = dashboard`, `name` + `memberIds` + layout/template).
 */
@Serializable
data class ArtifactCreateDto(
    val kind: String = "pin",
    val turnId: String? = null,
    val bubbleId: String? = null,
    val name: String,
    val memberIds: List<String> = emptyList(),
    val layoutJson: String? = null,
    val templateId: String? = null,
    val paramsJson: String? = null,
    val refreshMode: String = "manual",
)

/** `PATCH /v1/artifacts/{id}` body — null fields are left unchanged. */
@Serializable
data class ArtifactPatchDto(
    val name: String? = null,
    val paramsJson: String? = null,
    val layoutJson: String? = null,
    val memberIds: List<String>? = null,
    val refreshMode: String? = null,
)

/** Artifact response (JSON columns surfaced as nested JSON, not escaped strings). */
@Serializable
data class ArtifactDto(
    val artifactId: String,
    val kind: String,
    val name: String,
    val agentId: String? = null,
    val envelope: JsonElement? = null,
    val provenance: JsonElement? = null,
    val appliedContext: JsonElement? = null,
    val displayState: JsonElement? = null,
    val params: JsonElement? = null,
    val refreshMode: String,
    val paramMode: String? = null,
    val templateId: String? = null,
    val memberIds: List<String> = emptyList(),
    val layout: JsonElement? = null,
    val refreshedAt: String? = null,
    val refreshError: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ArtifactsListDto(
    val artifacts: List<ArtifactDto>,
)

private val artifactJson = Json { ignoreUnknownKeys = true }

private fun parseJson(s: String?): JsonElement? =
    s?.let { runCatching { artifactJson.parseToJsonElement(it) }.getOrNull() }

/** Map a stored [ArtifactRecord] to its wire DTO (JSON columns parsed to nested JSON). */
fun ArtifactRecord.toDto(): ArtifactDto =
    ArtifactDto(
        artifactId = artifactId.toString(),
        kind = kind.wire,
        name = name,
        agentId = agentId,
        envelope = parseJson(envelopeJson),
        provenance = parseJson(provenanceJson),
        appliedContext = parseJson(appliedContextJson),
        displayState = parseJson(displayStateJson),
        params = parseJson(paramsJson),
        refreshMode = refreshMode,
        paramMode = paramMode,
        templateId = templateId,
        memberIds = memberIds.map { it.toString() },
        layout = parseJson(layoutJson),
        refreshedAt = refreshedAt?.toString(),
        refreshError = refreshError,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
