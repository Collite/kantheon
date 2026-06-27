package org.tatrman.kantheon.iris.action

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Per-bubble shaping directives persisted in `iris_sessions.current_display`
 * (a JSON map keyed by `bubble_id`). On reload the BFF re-derives the shaped view
 * from the producing turn's cached rows + this state (Stage 3.2 T1).
 */
@Serializable
data class BubbleDisplay(
    val sort: SortState? = null,
    val filters: List<FilterState> = emptyList(),
    val page: Int? = null,
    val pageSize: Int? = null,
)

@Serializable
data class SortState(
    val column: String,
    val direction: String,
)

@Serializable
data class FilterState(
    val column: String,
    val operator: String,
    val value: JsonElement,
)
