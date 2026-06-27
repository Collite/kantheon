package org.tatrman.kantheon.iris.artifact

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.domain.ArtifactRecord
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.V2ActionRequest
import org.tatrman.kantheon.iris.stream.IrisStreamMux
import java.util.UUID

/** A refresh that cannot complete (no provenance, agent unavailable, …). */
class ArtifactRefreshException(
    message: String,
) : Exception(message)

/** Whether a pin's producing agent is a Golem Shem — capture (param mode) and
 *  refresh (executor selection) must agree on this one rule. */
internal fun isGolemAgent(agentId: String?): Boolean = agentId?.startsWith("golem") == true

/**
 * Deterministically re-executes a pin's captured query (PD-6, contracts §2.8) —
 * **never an LLM call**. Golem-kind pins re-run through the producing agent's
 * typed-action surface from the captured `ViewProvenance` (pattern_id + args);
 * Pythia-kind pins `replay`/`reproduce` (Pythia arc). Throws
 * [ArtifactRefreshException] on any failure so the caller can record an explicit
 * stale/error state — never a silently-wrong refresh.
 */
interface ArtifactExecutor {
    /** Re-execute [artifact]'s captured query; returns the fresh envelope JSON. */
    suspend fun reexecute(
        caller: CallerIdentity,
        artifact: ArtifactRecord,
    ): String
}

/**
 * Dispatches a refresh by the pin's producing agent. Golem-kind pins
 * (`agent_id` starts with `golem`) re-run via [GolemV2Client.reissueAction] from
 * the captured provenance; everything else (Pythia, native clients) has no
 * registered executor at Phase 4 and fails closed — mirroring the
 * `NO_AGENT_CLIENT` pin on the dispatch edge (the Pythia replay client lands in
 * the Pythia arc). Live golem replay fidelity is integration-deferred
 * (planning-conventions §4); the unit gate uses a fake executor.
 */
class RoutingArtifactExecutor(
    private val golem: GolemV2Client,
    private val mux: IrisStreamMux = IrisStreamMux(),
) : ArtifactExecutor {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    override suspend fun reexecute(
        caller: CallerIdentity,
        artifact: ArtifactRecord,
    ): String {
        if (!isGolemAgent(artifact.agentId)) {
            throw ArtifactRefreshException(
                "No refresh executor for agent '${artifact.agentId}' (Pythia replay lands in the Pythia arc)",
            )
        }
        val provenance =
            artifact.provenanceJson
                ?: throw ArtifactRefreshException("Pin has no captured provenance to re-execute")
        val bubbleId = bubbleIdOf(provenance)
        // Carry the pinned entity scope (applied_context) into the refresh request so
        // an entity-scoped pin re-runs at the same scope — dropping it would be a
        // silently-wrong refresh (PD-6). The agent re-applies it alongside the args.
        val payload = withAppliedContext(provenance, artifact.appliedContextJson)
        // A standalone pin has no live thread; refresh runs in a fresh, pin-scoped
        // thread carrying the captured pattern/args through the typed-action surface.
        // The mux normalises the v2 stream into a v1 envelope (terminal).
        val threadId = "artifact-${artifact.artifactId}"
        // One id correlates the mux turn with the dispatched request (logging/idempotency).
        val refreshId = "artifact-refresh-${UUID.randomUUID()}"
        val outcome =
            mux.run(
                refreshId,
                golem.reissueAction(
                    V2ActionRequest(threadId, bubbleId, "refresh", payload),
                    caller.userId,
                    refreshId,
                    caller.bearer,
                ),
            ) { /* envelope captured via outcome; no transport needed here */ }
        val envelope =
            outcome.envelope
                ?: throw ArtifactRefreshException(
                    outcome.errorCode?.let { "Refresh failed: $it" } ?: "Refresh produced no envelope",
                )
        // Keep the refreshed envelope anchored to the pin's stable bubble id.
        val anchored =
            if (bubbleId.isNotEmpty()) envelope.toBuilder().setBubbleId(bubbleId).build() else envelope
        return printer.print(anchored)
    }

    private fun bubbleIdOf(provenanceJson: String): String =
        runCatching {
            Json
                .parseToJsonElement(provenanceJson)
                .jsonObject["bubbleId"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        }.getOrDefault("")

    /** Merge the captured `applied_context` (entity scope) into the refresh payload
     *  under `appliedContext`, so the re-execution runs at the pinned scope. */
    private fun withAppliedContext(
        provenanceJson: String,
        appliedContextJson: String?,
    ): String {
        if (appliedContextJson.isNullOrBlank()) return provenanceJson
        return runCatching {
            val base = Json.parseToJsonElement(provenanceJson).jsonObject
            val applied = Json.parseToJsonElement(appliedContextJson)
            JsonObject(base + ("appliedContext" to applied)).toString()
        }.getOrDefault(provenanceJson)
    }
}
