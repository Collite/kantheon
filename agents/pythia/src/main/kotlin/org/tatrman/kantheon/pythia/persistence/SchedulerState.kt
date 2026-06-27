package org.tatrman.kantheon.pythia.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The checkpointable scheduler state (design §5, contracts §3a). Snapshotted on
 * entering any AWAITING_* (`reason = awaiting_*`), on plan revision
 * (`plan_revised`), and after each batch (`batch_completed`). Diffed field-by-
 * field into `pythia_checkpoints` and folded back on resume.
 *
 * PD-5 (§3a): per handle the checkpoint records the *recipe* (a Charon move spec
 * / Metis fit spec — deterministic given params + model_version) AND the Arrow
 * *fingerprint* at materialisation. Typed now; the liveness probes that consume
 * it land in Phase 4.
 */
@Serializable
data class SchedulerState(
    val frontier: List<String> = emptyList(),
    val inFlightStepIds: List<String> = emptyList(),
    val completedNodeIds: List<String> = emptyList(),
    val handleRecipes: Map<String, HandleRecipe> = emptyMap(),
    val budget: BudgetCounters = BudgetCounters(),
    val revision: Int = 0,
)

/** PD-5 per-handle recipe + Arrow fingerprint (consumed by the Phase-4 probes). */
@Serializable
data class HandleRecipe(
    val recipeKind: String, // "charon_move" | "metis_fit" | "query" | ...
    val recipeJson: String, // Rule-7 deterministic spec
    val arrowFingerprint: String, // content hash at original materialisation
)

/** The four budget dimensions tracked across a pause (design §3.1 Constraints). */
@Serializable
data class BudgetCounters(
    val usd: Double = 0.0,
    val tokens: Long = 0,
    val latencyMs: Long = 0,
    val stepCount: Int = 0,
)

/**
 * Field-level JSON diffing for [SchedulerState]. A diff is the JSON object of the
 * top-level fields whose serialised value changed (whole-field replacement — maps
 * and lists are replaced wholesale, so key removals are captured). Restoring folds
 * the default state through every diff in `seq` order; the `scheduler_state` column
 * carries the full snapshot only at the baseline (seq 0) for debuggability.
 */
object SchedulerStateCodec {
    val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    fun encode(state: SchedulerState): JsonObject =
        json.encodeToJsonElement(SchedulerState.serializer(), state).jsonObject

    fun decode(jsonStr: String): SchedulerState = json.decodeFromString(SchedulerState.serializer(), jsonStr)

    /** The field-level delta of [next] vs [prior] — keys whose serialised value differs. */
    fun diff(
        prior: SchedulerState,
        next: SchedulerState,
    ): JsonObject {
        val priorObj = encode(prior)
        val nextObj = encode(next)
        val changed = nextObj.filter { (k, v) -> priorObj[k] != v }
        return JsonObject(changed)
    }

    /** Apply a stored diff onto a base object (overlay changed top-level fields). */
    fun apply(
        base: JsonObject,
        diff: JsonObject,
    ): JsonObject = JsonObject(base + diff)

    /** Fold the default state through [diffs] (in seq order) to reconstruct the latest state. */
    fun fold(diffs: List<JsonObject>): SchedulerState {
        var acc = encode(SchedulerState())
        for (d in diffs) acc = apply(acc, d)
        return json.decodeFromJsonElement(SchedulerState.serializer(), acc)
    }
}
