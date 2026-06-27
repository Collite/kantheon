package org.tatrman.kantheon.iris.artifact

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.iris.action.BubbleDisplay
import org.tatrman.kantheon.iris.action.TableShaping
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.api.RoutingMetrics
import org.tatrman.kantheon.iris.audit.AuditStore
import org.tatrman.kantheon.iris.domain.ArtifactKind
import org.tatrman.kantheon.iris.domain.ArtifactRecord
import org.tatrman.kantheon.iris.domain.ArtifactStore
import org.tatrman.kantheon.iris.domain.NewArtifact
import org.tatrman.kantheon.iris.domain.SessionRecord
import org.tatrman.kantheon.iris.domain.TurnRecord
import java.time.Instant
import java.util.UUID

/**
 * Pin capture + refresh + dashboard assembly (PD-6, contracts §2.8). Capture
 * assembles a refreshable view from a turn's terminal envelope: the envelope
 * snapshot, the `ViewProvenance` (from the envelope's `current_view`), the
 * `applied_context` (EntityBindings in session scope), and the BFF display-state
 * slice. Refresh re-executes deterministically via [ArtifactExecutor] (never an
 * LLM call), re-applies the stored display state, and records an explicit
 * stale/error state on failure — never a silently-wrong refresh.
 */
class ArtifactService(
    private val store: ArtifactStore,
    private val executor: ArtifactExecutor,
    private val audit: AuditStore,
    private val metrics: RoutingMetrics = RoutingMetrics.NOOP,
    private val clock: () -> Instant = Instant::now,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val parser = JsonFormat.parser().ignoringUnknownFields()
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    /**
     * Capture a pin from [turn]'s terminal envelope (the [bubbleId] view). The
     * turn + session are owner-checked by the route. Returns null when the turn
     * carries no envelope (nothing to pin).
     */
    fun capturePin(
        caller: CallerIdentity,
        turn: TurnRecord,
        session: SessionRecord,
        bubbleId: String,
        name: String,
    ): ArtifactRecord? {
        val envelopeJson = turn.envelopeJson ?: return null
        val envelope =
            runCatching {
                FormatEnvelope.newBuilder().apply { parser.merge(envelopeJson, this) }.build()
            }.getOrNull() ?: return null

        val provenanceJson = provenanceFromView(envelope, bubbleId)
        val displaySlice = displaySliceFor(session, bubbleId)

        return store.create(
            NewArtifact(
                userId = caller.userId,
                tenantId = caller.tenantId,
                kind = ArtifactKind.PIN,
                name = name,
                agentId = turn.agentId,
                envelopeJson = printer.print(envelope),
                provenanceJson = provenanceJson,
                appliedContextJson = session.entityContextJson,
                displayStateJson = displaySlice,
                // pythia pins replay MOVING by default; golem pins re-run their pattern.
                paramMode = if (isGolemAgent(turn.agentId)) null else "moving",
            ),
        )
    }

    /** Create a dashboard (named, ordered pin collection + layout/template). */
    fun createDashboard(
        caller: CallerIdentity,
        name: String,
        memberIds: List<UUID>,
        layoutJson: String?,
        templateId: String?,
        paramsJson: String?,
        refreshMode: String,
    ): ArtifactRecord =
        store.create(
            NewArtifact(
                userId = caller.userId,
                tenantId = caller.tenantId,
                kind = ArtifactKind.DASHBOARD,
                name = name,
                memberIds = memberIds,
                layoutJson = layoutJson,
                templateId = templateId,
                paramsJson = paramsJson,
                refreshMode = refreshMode,
            ),
        )

    /**
     * Refresh a pin: re-execute deterministically, re-apply the captured display
     * state, persist the fresh envelope + `refreshed_at`, audit `artifact_refresh`.
     * On any failure the pin keeps its last good envelope and gains an explicit
     * `refresh_error` (never silently wrong). Returns the updated record (or null
     * if the artifact vanished); dashboards are a no-op pass-through.
     */
    suspend fun refresh(
        caller: CallerIdentity,
        artifact: ArtifactRecord,
    ): ArtifactRecord? {
        if (artifact.kind != ArtifactKind.PIN) return artifact
        val now = clock()
        return try {
            val freshJson = executor.reexecute(caller, artifact)
            val shaped = reapplyDisplay(freshJson, artifact.displayStateJson)
            val updated = store.recordRefresh(artifact.artifactId, now, shaped, null)
            auditRefresh(caller, artifact, "ok", null)
            metrics.recordArtifactRefresh("ok")
            updated
        } catch (e: ArtifactRefreshException) {
            val updated = store.recordRefresh(artifact.artifactId, now, null, e.message ?: "refresh failed")
            auditRefresh(caller, artifact, "error", e.message)
            metrics.recordArtifactRefresh("error")
            updated
        }
    }

    // --- capture helpers ---

    /** Map the envelope's `current_view` to a `common.v1.ViewProvenance` JSON. */
    private fun provenanceFromView(
        envelope: FormatEnvelope,
        bubbleId: String,
    ): String? {
        if (!envelope.hasCurrentView()) return null
        val v = envelope.currentView
        return buildJsonObject {
            put("patternId", v.patternId)
            put("argsJson", v.argsJson)
            put("sql", v.sql)
            put("bubbleId", bubbleId.ifEmpty { v.bubbleId.ifEmpty { envelope.bubbleId } })
            put("totalRows", v.totalRows)
        }.toString()
    }

    /** The per-bubble slice of the session's `current_display` map (sort/filter). */
    private fun displaySliceFor(
        session: SessionRecord,
        bubbleId: String,
    ): String? =
        runCatching {
            val map = json.decodeFromString<Map<String, BubbleDisplay>>(session.currentDisplayJson)
            map[bubbleId]?.let { json.encodeToString(it) }
        }.getOrNull()

    // --- refresh helpers ---

    /**
     * Re-apply the captured sort/filter to a freshly-executed envelope's rows.
     * Pagination is deliberately **not** re-applied: it is a transient view concern,
     * and a stale page index against a changed row set would silently yield an empty
     * slice (PD-6 "never silently wrong"). The fresh full result is returned shaped
     * by sort+filter only; the FE re-paginates.
     */
    private fun reapplyDisplay(
        envelopeJson: String,
        displayStateJson: String?,
    ): String {
        val display =
            displayStateJson?.let { runCatching { json.decodeFromString<BubbleDisplay>(it) }.getOrNull() }
                ?: return envelopeJson
        val envelope =
            runCatching {
                FormatEnvelope.newBuilder().apply { parser.merge(envelopeJson, this) }.build()
            }.getOrNull() ?: return envelopeJson

        val rows = parseRows(envelope)
        if (rows.isEmpty()) return envelopeJson
        val filters = display.filters.map { TableShaping.Filter(it.column, it.operator, it.value) }
        val sort = display.sort?.let { TableShaping.Sort(it.column, it.direction) }
        val shaped = TableShaping.shape(rows, filters, sort, page = null, pageSize = null)

        val b = envelope.toBuilder()
        b.contentJson = JsonArray(shaped).toString()
        return printer.print(b.build())
    }

    private fun parseRows(env: FormatEnvelope): List<JsonObject> {
        val content = env.contentJson.takeIf { env.hasContentJson() && it.isNotBlank() } ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(content).jsonArray.mapNotNull { it as? JsonObject }
        }.getOrDefault(emptyList())
    }

    private fun auditRefresh(
        caller: CallerIdentity,
        artifact: ArtifactRecord,
        result: String,
        error: String?,
    ) {
        audit.append(
            userId = caller.userId,
            eventKind = "artifact_refresh",
            payloadJson =
                buildJsonObject {
                    put("artifactId", artifact.artifactId.toString())
                    put("agentId", artifact.agentId ?: "")
                    put("result", result)
                    if (error != null) put("error", error)
                    artifact.provenanceJson?.let { put("provenance", json.parseToJsonElement(it)) }
                }.toString(),
            ts = clock(),
        )
    }
}
