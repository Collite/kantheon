package org.tatrman.kantheon.golem.execution

import kotlinx.serialization.json.JsonArray
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.common.v1.ViewProvenance
import org.tatrman.kantheon.envelope.render.tables.typedTableDetails
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.envelope.v1.PlanSource as EnvelopePlanSource
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.format.FormatEnricher
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.MiniPlanNode
import org.tatrman.kantheon.golem.v1.QueryNode
import org.tatrman.kantheon.golem.v1.ResourceUsage
import org.tatrman.kantheon.golem.v1.Status
import org.tatrman.kantheon.golem.v1.StepRecord
import org.tatrman.meta.v1.Language
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger(MiniPlanExecutor::class.java)

/** In-turn store of node results, keyed by node_id — later nodes read earlier ones. */
class HandleTable {
    private val byNode = LinkedHashMap<String, QueryResult>()

    fun put(
        nodeId: String,
        result: QueryResult,
    ) {
        byNode[nodeId] = result
    }

    operator fun get(nodeId: String): QueryResult? = byNode[nodeId]

    fun latest(): QueryResult? = byNode.values.lastOrNull()
}

/** The product of executing a mini-plan. */
data class ExecutionResult(
    val envelopes: List<FormatEnvelope>,
    val stepRecords: List<StepRecord>,
    val resourceUsage: ResourceUsage,
    val status: Status,
    /** "What the user is looking at" after this turn — the primary query's
     *  pattern/args/sql + the displayed bubble. The BFF snapshots it into the
     *  TurnPointer; a later AMEND/DRILL finds this turn by its `bubble_id`. Null
     *  when nothing was rendered (e.g. a query-only or failed-before-render plan). */
    val currentView: ViewProvenance? = null,
    /** Structured failure code when a node failed for a known reason (e.g.
     *  `PATTERN_PARAM_UNFILLED` — the parametrization-rail guard). Null on success
     *  or a generic node failure. Surfaced on the envelope in S3.1. */
    val errorCode: String? = null,
    /** Set when a required pattern param was unbound — the turn must ask the user via a
     *  `param_fill` clarification + resume (Δ2) rather than answer. */
    val paramFill: ParamFillNeed? = null,
)

/** A required-param-unbound signal: the first missing param's name + UX label (Δ2). */
data class ParamFillNeed(
    val paramName: String,
    val label: String,
)

/**
 * Executes a composed [MiniPlan] (architecture §4 `execute`): runs Query nodes in
 * order through the [QueryClient] (compile pre-check for FREE_SQL), holds results
 * in a [HandleTable], and renders a TABLE [FormatEnvelope] per Render node. Linear
 * deps at v1. **Partial failure** stops at the failed node but keeps the envelopes
 * produced so far and returns `STATUS_FAILED`. The full LLM format pipeline +
 * chips/drilldowns are Phase 3; a Reasoning node is recorded `SKIPPED` here.
 */
class MiniPlanExecutor(
    private val queryClient: QueryClient,
    private val rowCap: Int = 1000,
    private val targetDialect: String = "postgresql",
    private val agentVersion: String = "golem/v0.1.0",
    private val formatEnricher: FormatEnricher = FormatEnricher(),
    private val now: () -> Instant = Instant::now,
) {
    suspend fun execute(
        plan: MiniPlan,
        request: GolemRequest,
        model: ModelSnapshot?,
        bearer: String?,
    ): ExecutionResult {
        val handles = HandleTable()
        val steps = mutableListOf<StepRecord>()
        val envelopes = mutableListOf<FormatEnvelope>()
        var queryCount = 0
        var totalLatencyMs = 0L
        var failed = false
        var errorCode: String? = null
        var paramFill: ParamFillNeed? = null
        val view = PrimaryView()

        loop@ for (node in plan.nodesList) {
            val startedAt = now()
            try {
                when (node.kindCase) {
                    MiniPlanNode.KindCase.QUERY -> {
                        val ran = runQuery(node.query, model, bearer)
                        handles.put(node.nodeId, ran.result)
                        queryCount++
                        view.recordQuery(node.query, ran)
                        steps += step(node, "COMPLETED", startedAt, rowCount = ran.result.rowCount)
                    }
                    MiniPlanNode.KindCase.RENDER -> {
                        val input = renderInput(node, handles)
                        val rows = input?.rows ?: JsonArray(emptyList())
                        val totalRows = input?.rowCount ?: rows.size.toLong()
                        val base = baseTableEnvelope(rows, request, plan, node.render.caption)
                        val envelope = formatEnricher.enrich(base, rows, request, model, plan, totalRows)
                        envelopes += envelope
                        view.recordRender(envelope.bubbleId, totalRows)
                        steps += step(node, "COMPLETED", startedAt, rowCount = rows.size.toLong())
                    }
                    MiniPlanNode.KindCase.REASONING -> {
                        steps += step(node, "SKIPPED", startedAt) // LLM reasoning lands in Phase 3
                    }
                    else -> steps += step(node, "SKIPPED", startedAt)
                }
            } catch (e: ParamFillNeededException) {
                // Recoverable (Δ2): a required param is unbound → ask the user, don't fail.
                log.info("node '{}' needs param '{}' — emitting param_fill", node.nodeId, e.paramName)
                steps += step(node, "SKIPPED", startedAt)
                paramFill = ParamFillNeed(e.paramName, e.label)
                failed = true // stop the loop; the turn becomes a clarification upstream
            } catch (e: PatternParamUnfilledException) {
                // Fail-closed guard (T9): the under-bound query was never forwarded to query.
                log.info("node '{}' aborted: {} — {} not forwarded", node.nodeId, e.message, e.errorCode)
                steps += step(node, "FAILED", startedAt, error = e.message)
                failed = true
                errorCode = e.errorCode
            } catch (e: Exception) {
                log.info("node '{}' failed: {} — partial answer, status FAILED", node.nodeId, e.message)
                steps += step(node, "FAILED", startedAt, error = e.message)
                failed = true
            } finally {
                // Count the node's latency even on failure (the failed node did work).
                totalLatencyMs += latencyMs(startedAt)
            }
            if (failed) break@loop
        }

        return ExecutionResult(
            envelopes = envelopes,
            stepRecords = steps,
            resourceUsage =
                ResourceUsage
                    .newBuilder()
                    .setQueryCount(queryCount)
                    .setTotalLatencyMs(totalLatencyMs)
                    .build(),
            status = if (failed) Status.STATUS_FAILED else Status.STATUS_DONE,
            currentView = view.build(),
            errorCode = errorCode,
            paramFill = paramFill,
        )
    }

    /** The product of executing one Query node — the rows plus the SQL actually run
     *  (the compiled SQL for FREE_SQL, the resolved pattern source otherwise). */
    private data class RanQuery(
        val result: QueryResult,
        val sql: String,
    )

    private suspend fun runQuery(
        q: QueryNode,
        model: ModelSnapshot?,
        bearer: String?,
    ): RanQuery {
        // A PATTERN node carries the pattern id: send the `sql_template` VERBATIM ({name} intact)
        // + the typed parameters the rail builds from the declared params; Translate's restored
        // ParameterBridge rewrites {name} → ? downstream (T7). The rail's guard fails closed on an
        // under-bound template (PATTERN_PARAM_UNFILLED, T9) — the query is never forwarded.
        if (q.hasPatternId() && model != null) {
            val pq =
                model.patternQuery(q.patternId)
                    ?: throw QueryException("pattern '${q.patternId}' is not in the model")
            val source = pq.sourceText
            val paramsJson =
                when (val rail = PatternParameterRail.prepare(q.paramsJson, pq)) {
                    is RailResult.Bound -> rail.parametersJson
                    is RailResult.Unfilled -> throw PatternParamUnfilledException(rail.placeholders)
                    // A required param is unbound → ask the user (param_fill, Δ2), not a hard fail.
                    is RailResult.MissingRequired -> {
                        val name = rail.names.first()
                        val label =
                            pq.parametersList
                                .firstOrNull { it.name == name }
                                ?.label
                                ?.ifBlank { name } ?: name
                        throw ParamFillNeededException(name, label)
                    }
                }
            // A resolved pattern's language is a model property (SQL for every hartland query),
            // not the LLM's to guess. Honouring q.sourceLanguage here sent SQL patterns as
            // "transdsl" → translator_rejected → 0 rows (Stage 3.3 T7). Use the pattern's own
            // language; fall back to the plan value only if the model left it unspecified.
            val patternLanguage =
                pq.sourceLanguage
                    .takeIf { it != Language.LANGUAGE_UNSPECIFIED }
                    ?.name
                    ?.lowercase()
                    ?: q.sourceLanguage
            return RanQuery(queryClient.query(source, patternLanguage, paramsJson, rowCap, bearer), source)
        }
        // Raw source (free-SQL / composer-emitted SQL): no typed parameter rail.
        val source = q.source
        if (!q.compileFirst) {
            return RanQuery(queryClient.query(source, q.sourceLanguage, q.paramsJson, rowCap, bearer), source)
        }
        // FREE_SQL: compile the source first, then run the compiled SQL.
        val compiled = queryClient.compile(source, q.sourceLanguage, targetDialect, bearer)
        return RanQuery(
            queryClient.query(compiled.compiledSql, "sql", q.paramsJson, rowCap, bearer),
            compiled.compiledSql,
        )
    }

    /**
     * Accumulates the turn's [ViewProvenance] from its first query (pattern/args/sql)
     * and first rendered bubble (bubble_id + displayed row count). `build()` returns
     * null until something is rendered — a current_view anchors a visible bubble.
     */
    private inner class PrimaryView {
        private var patternId: String? = null
        private var argsJson: String? = null
        private var sql: String? = null
        private var queryRows = 0L
        private var bubbleId: String? = null
        private var displayedRows: Long? = null

        fun recordQuery(
            q: QueryNode,
            ran: RanQuery,
        ) {
            if (sql != null) return // first query is the primary view
            patternId = if (q.hasPatternId()) q.patternId.ifBlank { null } else null
            argsJson = q.paramsJson.ifBlank { null }
            sql = ran.sql
            queryRows = ran.result.rowCount
        }

        fun recordRender(
            bubble: String,
            rows: Long,
        ) {
            if (bubbleId != null) return
            bubbleId = bubble
            displayedRows = rows
        }

        fun build(): ViewProvenance? {
            val bubble = bubbleId ?: return null
            val b = ViewProvenance.newBuilder().setBubbleId(bubble)
            patternId?.let { b.patternId = it }
            argsJson?.let { b.argsJson = it }
            sql?.let { b.sql = it }
            b.totalRows = displayedRows ?: queryRows
            return b.build()
        }
    }

    private fun renderInput(
        node: MiniPlanNode,
        handles: HandleTable,
    ): QueryResult? {
        val inputId = node.render.inputNodeIdsList.firstOrNull()
        return inputId?.let { handles[it] } ?: handles.latest()
    }

    /** The executor's base TABLE envelope; the [FormatEnricher] layers chips/drilldowns/kind on top. */
    private fun baseTableEnvelope(
        rows: JsonArray,
        request: GolemRequest,
        plan: MiniPlan,
        caption: String?,
    ): FormatEnvelope.Builder {
        // Typed column-spec emitter (Δ5): headers + per-column directives (numeric→right,
        // float→rounded `number` + `%.2f`, integers raw). `content` stays the rows array;
        // the FE pages client-side over it (total_rows rides on current_view).
        val table = typedTableDetails(rows)
        val b =
            FormatEnvelope
                .newBuilder()
                .setBubbleId(UUID.randomUUID().toString())
                .setTurnId(request.id)
                .setContentJson(rows.toString())
                .setFormat(FormatSpec.newBuilder().setKind(FormatKind.TABLE).setTable(table))
                .setPlanSource(
                    EnvelopePlanSource.forNumber(plan.source.number) ?: EnvelopePlanSource.PLAN_SOURCE_UNSPECIFIED,
                ).setPlanScore(plan.confidence)
                .setCreatedAt(now().toString())
                .setAgentVersion(agentVersion)
                .setAgentId(request.golemId)
        if (!caption.isNullOrBlank()) b.text = caption
        return b
    }

    private fun step(
        node: MiniPlanNode,
        status: String,
        startedAt: Instant,
        rowCount: Long? = null,
        error: String? = null,
    ): StepRecord {
        val b =
            StepRecord
                .newBuilder()
                .setNodeId(node.nodeId)
                .setNodeKind(node.kindCase.name)
                .setStatus(status)
                .setLatencyMs(latencyMs(startedAt))
        rowCount?.let { b.rowCount = it }
        error?.let { b.error = it }
        return b.build()
    }

    private fun latencyMs(startedAt: Instant): Long = (now().toEpochMilli() - startedAt.toEpochMilli()).coerceAtLeast(0)
}
