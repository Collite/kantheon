package org.tatrman.kantheon.iris.dispatch.golemv2

import kotlinx.serialization.Serializable

// Quarantined new-golem /v2 wire DTOs (contracts §5). Nothing outside this
// package may import them — deleted at the Golem-rewrite cutover. Field names
// are the v2 wire (snake_case) via @SerialName where they differ from Kotlin.

@Serializable
data class V2SessionStartRequest(
    val thread_id: String,
    val locale: String = "cs",
    val user_id: String? = null,
)

@Serializable
data class V2StaticChip(
    val display: String = "",
    val prompt: String = "",
)

@Serializable
data class V2SessionStartResponse(
    val thread_id: String,
    val packages: List<String> = emptyList(),
    val static_chips: List<V2StaticChip> = emptyList(),
    val example_questions: List<String> = emptyList(),
    val agent_version: String = "",
)

@Serializable
data class V2ChatRequest(
    val thread_id: String,
    val user_text: String,
    val desired_format: String? = null,
)

@Serializable
data class V2ActionRequest(
    val thread_id: String,
    val bubble_id: String,
    val kind: String,
    val payload_json: String,
)

@Serializable
data class V2ResumeRequest(
    val thread_id: String,
    val resume_token: String,
    val selected_option_id: String? = null,
    val free_text_answer: String? = null,
)

@Serializable
data class V2RefreshRequest(
    val service: String? = null,
)

@Serializable
data class V2RefreshResultItem(
    val service: String = "",
    val status: String = "",
    val detail: String? = null,
    val version: String? = null,
)

@Serializable
data class V2RefreshResponse(
    val results: List<V2RefreshResultItem> = emptyList(),
)
