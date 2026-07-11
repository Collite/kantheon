package org.tatrman.kantheon.pythia.dataplane

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.tatrman.transfer.v1.DbTable
import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.SeaweedBlob
import org.tatrman.transfer.v1.WorkerSessionDf
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.persistence.HandleRecipe
import org.tatrman.kantheon.pythia.v1.Handle
import org.tatrman.kantheon.pythia.v1.LooseEnd
import org.tatrman.kantheon.pythia.v1.LooseEndReason
import org.tatrman.kantheon.pythia.v1.LooseEndSource

/**
 * The durable, deterministic recipe to rebuild a handle's data (PD-5, contracts
 * §3a) — the *source* the original move read from (a durable origin: a DbTable or
 * a Seaweed blob) and the move verb. The *target* is the handle's own current
 * location; re-materialisation re-issues the move source→target. Carried in the
 * checkpoint as [HandleRecipe.recipeJson] under `recipeKind = "charon_move"`.
 */
@Serializable
data class CharonMoveRecipe(
    val verb: String, // "stage" | "materialize"
    val sourceConnection: String = "",
    val sourceSchema: String = "",
    val sourceTable: String = "",
    val sourceBucket: String = "",
    val sourceKey: String = "",
) {
    fun sourceLocation(): Location =
        if (sourceTable.isNotBlank()) {
            Location
                .newBuilder()
                .setDbTable(
                    DbTable
                        .newBuilder()
                        .setConnectionId(
                            sourceConnection,
                        ).setSchema(sourceSchema)
                        .setTable(sourceTable),
                ).build()
        } else {
            Location.newBuilder().setSeaweed(SeaweedBlob.newBuilder().setBucket(sourceBucket).setKey(sourceKey)).build()
        }
}

/** The result of a resume-time probe pass over the handles the resumed plan needs. */
data class ResumeResult(
    val rematerialised: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val looseEnds: List<LooseEnd> = emptyList(),
)

/**
 * PD-5 resume semantics (contracts §3a). On resume from any AWAITING_*, for each
 * handle the resumed plan still needs, probe liveness (**Charon `Describe`** →
 * `exists` + `schema_fingerprint`) and:
 *   - **dead** (`exists = false`) → lazily re-materialise from the checkpointed
 *     [CharonMoveRecipe] (deterministic given the durable source). If the
 *     re-materialised fingerprint differs from the checkpointed one, also raise the
 *     drift warning + LooseEnd.
 *   - **live but drifted** (fingerprint ≠ checkpointed) → a Rule-6 warning + a
 *     `LooseEnd` ("inputs changed during pause: <handle>"); **never a hard fail**,
 *     never silent epoch-mixing.
 *
 * Handles with no Charon mapping (LiveQueryRef / PgResultSnapshot) and handles with
 * no recipe are skipped (nothing to probe / nothing to rebuild).
 */
class ResumeProber(
    private val charon: CharonClient,
) {
    private val log = LoggerFactory.getLogger(ResumeProber::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun probe(
        neededHandleIds: Collection<String>,
        handles: HandleTable,
        recipes: Map<String, HandleRecipe>,
    ): ResumeResult {
        val rematerialised = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val looseEnds = mutableListOf<LooseEnd>()

        for (handleId in neededHandleIds) {
            val handle = handles.get(handleId) ?: continue
            val location = runCatching { HandleLocationMapping.toLocation(handle) }.getOrNull() ?: continue
            val recipe = recipes[handleId]
            val checkpointedFp = recipe?.arrowFingerprint.orEmpty()

            val describe = runCatching { charon.describe(location) }.getOrNull() ?: continue
            if (!describe.exists) {
                val moveRecipe = recipe?.let { decode(it) }
                if (moveRecipe == null) {
                    log.warn("resume: handle {} is dead but has no recipe — leaving as-is", handleId)
                    continue
                }
                val result =
                    when (moveRecipe.verb) {
                        "stage" -> charon.stage(moveRecipe.sourceLocation(), workerTargetOf(handle))
                        else -> charon.materialize(moveRecipe.sourceLocation(), location)
                    }
                rematerialised += handleId
                if (checkpointedFp.isNotBlank() && result.schemaFingerprint != checkpointedFp) {
                    warnings += driftWarning(handleId)
                    looseEnds += driftLooseEnd(handle)
                }
            } else if (checkpointedFp.isNotBlank() &&
                describe.schemaFingerprint.isNotBlank() &&
                describe.schemaFingerprint != checkpointedFp
            ) {
                warnings += driftWarning(handleId)
                looseEnds += driftLooseEnd(handle)
            }
        }
        return ResumeResult(rematerialised, warnings, looseEnds)
    }

    private fun decode(recipe: HandleRecipe): CharonMoveRecipe? =
        if (recipe.recipeKind != "charon_move") {
            null
        } else {
            runCatching { json.decodeFromString(CharonMoveRecipe.serializer(), recipe.recipeJson) }.getOrNull()
        }

    // The Pythia handle carries no engine discriminator, so a re-staged worker DF defaults to
    // POLARS (the WorkerKind zero value). A dead *Metis*-staged DF is rebuilt via the Metis re-fit
    // path (ModelNodeExecutor), not here, so the POLARS default is correct for the handles that
    // actually reach this re-stage branch; revisit if a non-Polars DF is ever re-staged via Charon.
    private fun workerTargetOf(handle: Handle): WorkerSessionDf =
        WorkerSessionDf
            .newBuilder()
            .setSessionId(handle.workerDf.sessionId)
            .setDfName(handle.workerDf.dfName)
            .build()

    private fun driftWarning(handleId: String): String = "inputs changed during pause: $handleId"

    private fun driftLooseEnd(handle: Handle): LooseEnd =
        LooseEnd
            .newBuilder()
            .setSource(LooseEndSource.LOOSE_END_EXECUTION_TIME)
            .setReason(LooseEndReason.LOOSE_END_REASON_UNSPECIFIED)
            .setWhy("inputs changed during pause: ${handle.handleId} (re-materialised with a different fingerprint)")
            .build()
}
