package org.tatrman.kantheon.iris.action

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.TableFilterSpec
import org.tatrman.kantheon.envelope.v1.TableSortSpec
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.api.RoutingMetrics
import org.tatrman.kantheon.iris.audit.AuditStore
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.V2ActionRequest
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.stream.IrisStreamMux
import org.tatrman.kantheon.iris.v1.DoneEvent
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import java.time.Instant
import java.util.UUID

/** A parsed sort/filter/paginate directive (contracts §2.4). */
sealed interface ShapeDirective {
    data class SortDir(
        val column: String,
        val direction: String,
    ) : ShapeDirective

    data class FilterDir(
        val column: String,
        val operator: String,
        val value: JsonElement,
    ) : ShapeDirective

    data class PaginateDir(
        val page: Int,
        val pageSize: Int,
    ) : ShapeDirective
}

/**
 * Serves the data-shaping typed actions (Stage 3.2 T1): sort / filter / paginate
 * applied **BFF-side** on the producing turn's cached rows (`content_json`), with
 * no agent round-trip. The merged per-bubble directives persist to
 * `iris_sessions.current_display` so reload re-applies them; a paginate beyond the
 * cached page (when more rows exist) falls through to a refetch via
 * [GolemV2Client.reissueAction]. Each shaping emits a **replacing** envelope with
 * the **same `bubble_id`**, then `done`; audited as `typed_action`.
 */
class TypedActionDispatcher(
    private val store: SessionStore,
    private val golem: GolemV2Client,
    private val audit: AuditStore,
    private val mux: IrisStreamMux = IrisStreamMux(),
    private val metrics: RoutingMetrics = RoutingMetrics.NOOP,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(TypedActionDispatcher::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()
    private val parser = JsonFormat.parser().ignoringUnknownFields()

    /** Parse the per-kind payload; null = malformed (route → 400). */
    fun parse(
        kind: String,
        payloadJson: String,
    ): ShapeDirective? =
        runCatching {
            val o = json.parseToJsonElement(payloadJson).jsonObject
            when (kind) {
                "sort" ->
                    ShapeDirective.SortDir(
                        o["column"]!!.jsonPrimitive.content,
                        o["direction"]?.jsonPrimitive?.content ?: "asc",
                    )
                "filter" -> {
                    val operator = o["operator"]!!.jsonPrimitive.content
                    // Reject unknown operators at the edge: an unrecognised operator
                    // would otherwise be persisted and silently filter out every row.
                    if (operator !in TableShaping.VALID_OPERATORS) return null
                    ShapeDirective.FilterDir(
                        o["column"]!!.jsonPrimitive.content,
                        operator,
                        o["value"] ?: JsonPrimitive(""),
                    )
                }
                "paginate" -> {
                    val page = o["page"]!!.jsonPrimitive.int
                    val pageSize = o["pageSize"]!!.jsonPrimitive.int
                    // Bound the inputs (→ 400) so a hostile/huge page can't overflow or
                    // drive an absurd slice; page is 1-based.
                    if (page < 1 || pageSize < 1 || pageSize > TableShaping.MAX_PAGE_SIZE) return null
                    ShapeDirective.PaginateDir(page, pageSize)
                }
                else -> null
            }
        }.getOrNull()

    /** True if [kind] is a data-shaping action this dispatcher serves. */
    fun handles(kind: String): Boolean = kind == "sort" || kind == "filter" || kind == "paginate"

    /** True if [kind] is a navigation action (drilldown opens a new view). */
    fun handlesSelect(kind: String): Boolean = kind == "select_row"

    /** Parse a `select_row` payload's `rowIndex`; null = malformed (route → 400). */
    fun parseRowIndex(payloadJson: String): Int? =
        runCatching {
            json
                .parseToJsonElement(payloadJson)
                .jsonObject["rowIndex"]!!
                .jsonPrimitive.int
        }.getOrNull()

    /**
     * `select_row` (T2): map the selected row through the bubble's `Drilldown`
     * (`target_pattern_id` + `arg_mapping`, `scope=row`) into a re-issued query
     * against the producing agent, and stream the result as a **new bubble** (a
     * drilldown opens a new view, not a replace). The result persists as a new
     * turn; audited as `typed_action`.
     */
    suspend fun select(
        caller: CallerIdentity,
        sessionId: UUID,
        bubbleId: String,
        rowIndex: Int,
        correlationId: String,
        emit: suspend (IrisStreamEvent) -> Unit,
    ) {
        val actionTurnId = UUID.randomUUID().toString()
        val cached = findBubbleEnvelope(sessionId, bubbleId)
        if (cached == null) {
            emitError(actionTurnId, "BUBBLE_NOT_FOUND", "No cached bubble '$bubbleId' to drill into", emit)
            return
        }
        val rows = parseRows(cached)
        val row = rows.getOrNull(rowIndex)
        if (row == null) {
            emitError(actionTurnId, "ROW_OUT_OF_RANGE", "Row $rowIndex is not in the cached bubble", emit)
            return
        }
        val drill = cached.drilldownsList.firstOrNull { it.scope == "row" } ?: cached.drilldownsList.firstOrNull()
        if (drill == null) {
            emitError(actionTurnId, "NO_DRILLDOWN", "No drilldown is available on this bubble", emit)
            return
        }
        // args = arg_mapping{argName -> rowColumn} resolved against the selected row.
        val missingColumns = drill.argMappingMap.values.filter { row[it] == null }
        if (missingColumns.isNotEmpty()) {
            // A mapped column absent from the cached row yields a partial drilldown
            // arg set (schema drift / null cell) — surface it rather than silently dropping.
            log.warn(
                "select_row drilldown on bubble {} is missing mapped column(s) {} — drilldown args will be partial",
                bubbleId,
                missingColumns,
            )
        }
        val args =
            buildJsonObject {
                drill.argMappingMap.forEach { (argName, rowColumn) -> row[rowColumn]?.let { put(argName, it) } }
            }
        val reqPayload =
            buildJsonObject {
                put("patternId", JsonPrimitive(drill.targetPatternId))
                put("rowIndex", JsonPrimitive(rowIndex))
                put("args", args)
            }.toString()
        val threadId = store.getV2Thread(sessionId) ?: sessionId.toString()
        val outcome =
            mux.run(
                actionTurnId,
                golem.reissueAction(
                    V2ActionRequest(threadId, bubbleId, "select_row", reqPayload),
                    caller.userId,
                    correlationId,
                    caller.bearer,
                ),
                emit,
            )
        // A drilldown opens a NEW view → persist a new turn (not a current_display tweak).
        store.appendTurn(
            org.tatrman.kantheon.iris.domain.NewTurn(
                sessionId = sessionId,
                agentId = "golem-v2",
                question = "↳ ${drill.display.ifBlank { drill.targetPatternId }}",
                status = outcome.status,
                envelopeJson = outcome.envelope?.let { printer.print(it) },
                displayedBlockIds =
                    outcome.envelope
                        ?.bubbleId
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { listOf(it) } ?: emptyList(),
            ),
        )
        auditAction(caller, "select_row", bubbleId, refetch = true)
    }

    suspend fun shape(
        caller: CallerIdentity,
        sessionId: UUID,
        bubbleId: String,
        kind: String,
        payloadJson: String,
        directive: ShapeDirective,
        correlationId: String,
        emit: suspend (IrisStreamEvent) -> Unit,
    ) {
        val actionTurnId = UUID.randomUUID().toString()
        val cached = findBubbleEnvelope(sessionId, bubbleId)
        if (cached == null) {
            emitError(actionTurnId, "BUBBLE_NOT_FOUND", "No cached bubble '$bubbleId' to reshape", emit)
            return
        }
        val rows = parseRows(cached)
        val merged = mergeDirective(loadDisplay(sessionId, bubbleId), directive)
        persistDisplay(sessionId, bubbleId, merged)

        val cachedCount = rows.size
        val totalRows =
            if (cached.hasFormat() && cached.format.hasTable() && cached.format.table.hasPaging()) {
                cached.format.table.paging.totalRows
            } else {
                cachedCount.toLong()
            }
        // Refetch when a page is requested beyond what's cached and more rows exist.
        val needsRefetch =
            merged.page != null &&
                merged.pageSize != null &&
                (merged.page - 1).toLong() * merged.pageSize >= cachedCount &&
                totalRows > cachedCount

        if (needsRefetch) {
            refetch(caller, sessionId, bubbleId, kind, payloadJson, actionTurnId, correlationId, emit)
            auditAction(caller, kind, bubbleId, refetch = true)
            return
        }

        val filters = merged.filters.map { TableShaping.Filter(it.column, it.operator, it.value) }
        val sort = merged.sort?.let { TableShaping.Sort(it.column, it.direction) }
        val filteredCount = TableShaping.shape(rows, filters, null, null, null).size
        val shaped = TableShaping.shape(rows, filters, sort, merged.page, merged.pageSize)

        val replacing = rebuild(cached, shaped, merged, filteredCount.toLong())
        emit(envelopeEvent(actionTurnId, 1, replacing))
        emit(doneEvent(actionTurnId, 2, "done"))
        auditAction(caller, kind, bubbleId, refetch = false)
    }

    private suspend fun refetch(
        caller: CallerIdentity,
        sessionId: UUID,
        bubbleId: String,
        kind: String,
        payloadJson: String,
        actionTurnId: String,
        correlationId: String,
        emit: suspend (IrisStreamEvent) -> Unit,
    ) {
        val threadId = store.getV2Thread(sessionId) ?: sessionId.toString()
        mux.run(
            actionTurnId,
            golem.reissueAction(
                V2ActionRequest(threadId, bubbleId, kind, payloadJson),
                caller.userId,
                correlationId,
                caller.bearer,
            ),
            emit,
        )
    }

    private fun findBubbleEnvelope(
        sessionId: UUID,
        bubbleId: String,
    ): FormatEnvelope? =
        store
            .getTurns(sessionId, includeDiscarded = true)
            .asReversed()
            .firstNotNullOfOrNull { turn ->
                val raw = turn.envelopeJson ?: return@firstNotNullOfOrNull null
                val env =
                    runCatching {
                        FormatEnvelope
                            .newBuilder()
                            .apply {
                                parser.merge(
                                    raw,
                                    this,
                                )
                            }.build()
                    }.getOrNull()
                env?.takeIf { it.bubbleId == bubbleId }
            }

    private fun parseRows(env: FormatEnvelope): List<JsonObject> {
        val content = env.contentJson.takeIf { env.hasContentJson() && it.isNotBlank() } ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(content).jsonArray.mapNotNull { it as? JsonObject }
        }.getOrDefault(emptyList())
    }

    private fun rebuild(
        cached: FormatEnvelope,
        shaped: List<JsonObject>,
        display: BubbleDisplay,
        totalRows: Long,
    ): FormatEnvelope {
        val b = cached.toBuilder()
        b.contentJson = shaped.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toString() }
        if (b.hasFormat() && b.format.hasTable()) {
            val t = b.formatBuilder.tableBuilder
            t.clearSort()
            display.sort?.let { t.addSort(TableSortSpec.newBuilder().setColumn(it.column).setDirection(it.direction)) }
            t.clearFilters()
            display.filters.forEach {
                t.addFilters(
                    TableFilterSpec
                        .newBuilder()
                        .setColumn(it.column)
                        .setOperator(it.operator)
                        .setValueJson(it.value.toString()),
                )
            }
            val paging = t.pagingBuilder
            display.page?.let { paging.page = it }
            display.pageSize?.let { paging.pageSize = it }
            paging.totalRows = totalRows
        }
        return b.build()
    }

    // --- current_display persistence (per-bubble directive map) ---

    private fun loadDisplay(
        sessionId: UUID,
        bubbleId: String,
    ): BubbleDisplay {
        val session = store.getSession(sessionId) ?: return BubbleDisplay()
        val map =
            runCatching { json.decodeFromString<Map<String, BubbleDisplay>>(session.currentDisplayJson) }
                .getOrDefault(emptyMap())
        return map[bubbleId] ?: BubbleDisplay()
    }

    private fun persistDisplay(
        sessionId: UUID,
        bubbleId: String,
        display: BubbleDisplay,
    ) {
        val session = store.getSession(sessionId) ?: return
        val map =
            runCatching {
                json.decodeFromString<Map<String, BubbleDisplay>>(session.currentDisplayJson).toMutableMap()
            }.getOrDefault(mutableMapOf())
        map[bubbleId] = display
        store.setCurrentDisplay(sessionId, json.encodeToString(map))
    }

    private fun mergeDirective(
        cur: BubbleDisplay,
        directive: ShapeDirective,
    ): BubbleDisplay =
        when (directive) {
            is ShapeDirective.SortDir -> cur.copy(sort = SortState(directive.column, directive.direction))
            is ShapeDirective.PaginateDir -> cur.copy(page = directive.page, pageSize = directive.pageSize)
            is ShapeDirective.FilterDir -> {
                // A new filter on a column replaces a prior one on the same column;
                // a blank value is the FE's "clear this column" signal → drop it
                // entirely rather than persisting a match-all no-op filter.
                val withoutColumn = cur.filters.filterNot { it.column == directive.column }
                val cleared = (directive.value as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()
                cur.copy(
                    filters =
                        if (cleared) {
                            withoutColumn
                        } else {
                            withoutColumn + FilterState(directive.column, directive.operator, directive.value)
                        },
                )
            }
        }

    private fun auditAction(
        caller: CallerIdentity,
        kind: String,
        bubbleId: String,
        refetch: Boolean,
    ) {
        metrics.recordTypedAction(kind)
        audit.append(
            userId = caller.userId,
            eventKind = "typed_action",
            payloadJson =
                buildJsonObject {
                    put("kind", JsonPrimitive(kind))
                    put("bubbleId", JsonPrimitive(bubbleId))
                    put("refetch", JsonPrimitive(refetch))
                }.toString(),
            ts = Instant.now(),
        )
    }

    private suspend fun emitError(
        turnId: String,
        code: String,
        message: String,
        emit: suspend (IrisStreamEvent) -> Unit,
    ) {
        emit(
            IrisStreamEvent
                .newBuilder()
                .setTurnId(turnId)
                .setSequence(1)
                .setError(
                    org.tatrman.kantheon.iris.v1.ErrorEvent
                        .newBuilder()
                        .setCode(code)
                        .setMessage(message)
                        .setRecoverable(false),
                ).build(),
        )
        emit(doneEvent(turnId, 2, "failed"))
    }

    private fun envelopeEvent(
        turnId: String,
        seq: Long,
        env: FormatEnvelope,
    ): IrisStreamEvent =
        IrisStreamEvent
            .newBuilder()
            .setTurnId(turnId)
            .setSequence(seq)
            .setEnvelope(env)
            .build()

    private fun doneEvent(
        turnId: String,
        seq: Long,
        outcome: String,
    ): IrisStreamEvent =
        IrisStreamEvent
            .newBuilder()
            .setTurnId(turnId)
            .setSequence(seq)
            .setDone(DoneEvent.newBuilder().setOutcome(outcome))
            .build()
}
