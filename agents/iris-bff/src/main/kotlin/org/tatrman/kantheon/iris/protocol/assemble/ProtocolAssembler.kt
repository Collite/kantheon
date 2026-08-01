package org.tatrman.kantheon.iris.protocol.assemble

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.iris.protocol.config.ProtocolConfig
import org.tatrman.kantheon.iris.protocol.model.DocumentBuilder
import org.tatrman.kantheon.iris.protocol.record.ProtocolRecordStore
import org.tatrman.kantheon.iris.protocol.redact.RedactionChain
import org.tatrman.kantheon.iris.protocol.sections.TurnFacts
import org.tatrman.kantheon.iris.protocol.sources.ExplainClient
import org.tatrman.kantheon.iris.protocol.sources.ExplainSource
import org.tatrman.kantheon.iris.protocol.sources.GatewayLogsClient
import org.tatrman.kantheon.iris.protocol.sources.GatewaySource
import org.tatrman.kantheon.iris.protocol.sources.LokiClient
import org.tatrman.kantheon.iris.protocol.sources.LokiSource
import org.tatrman.kantheon.iris.protocol.sources.ProtocolSources
import org.tatrman.kantheon.iris.protocol.sources.SourceOutcome
import org.tatrman.kantheon.iris.protocol.sources.SourceStatus
import org.tatrman.kantheon.iris.protocol.sources.TempoClient
import org.tatrman.kantheon.iris.protocol.sources.TempoSource
import org.tatrman.kantheon.protocol.v1.ProtocolDocument
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import org.tatrman.kantheon.protocol.v1.Scope
import org.tatrman.kantheon.protocol.v1.SectionStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private val log = LoggerFactory.getLogger(ProtocolAssembler::class.java)

/**
 * Turns a scope into a rendered-ready [ProtocolDocument] (architecture §3.1).
 *
 * The pipeline is fixed and its order is load-bearing:
 * **records → sources (parallel) → build → redact (floor, then config) → receipts last.**
 *
 * **It cannot fail into an error shape.** Every source is fetched in its own
 * isolated coroutine and every failure becomes a `Degraded` outcome that lands in
 * the receipts; there is no path from "a source was down" to "the request 500s"
 * (P-4). The only thing that can legitimately fail is *finding no turns at all*,
 * and that is the caller's 404, not this class's exception.
 */
class ProtocolAssembler(
    private val records: ProtocolRecordStore,
    private val config: ProtocolConfig,
    private val gateway: GatewayLogsClient?,
    private val loki: LokiClient?,
    private val tempo: TempoClient?,
    private val explain: ExplainClient?,
    private val registry: MeterRegistry = SimpleMeterRegistry(),
    private val clock: () -> Instant = Instant::now,
    private val ids: () -> UUID = UUID::randomUUID,
    private val estate: String = "kantheon",
    private val assemblerVersion: String = "1.0",
) {
    /** What the caller asked for; `turns` are supplied by the route from the session store. */
    data class Request(
        val sessionId: UUID,
        val scope: Scope,
        val turns: List<TurnFacts>,
        val bearer: String,
        val turnCountTotal: Int = turns.size,
        val sessionCreatedAt: String = "",
        val profileName: String? = null,
    )

    suspend fun assemble(req: Request): ProtocolDocument {
        val startNanos = System.nanoTime()
        val scopeLabel = req.scope.label()

        val scoped = selectTurns(req)
        val inScope = scoped.takeLast(config.caps.maxTurns)
        val turnsDropped = scoped.size - inScope.size
        val recordsByTurn = readRecords(req, inScope)

        val sources = fetchSources(inScope, recordsByTurn, req.bearer)

        val doc =
            DocumentBuilder.build(
                DocumentBuilder.Request(
                    protocolId = ids().toString(),
                    sessionId = req.sessionId.toString(),
                    scope = req.scope,
                    generatedAt = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC).format(ISO),
                    turns =
                        inScope.map { f ->
                            DocumentBuilder.TurnInput(
                                facts = f,
                                record = recordsByTurn[f.turnId] ?: ProtocolRecord.getDefaultInstance(),
                            )
                        },
                    sources = sources,
                    config = config,
                    profileName = req.profileName,
                    turnCountTotal = req.turnCountTotal,
                    sessionCreatedAt = req.sessionCreatedAt,
                    estate = estate,
                    assemblerVersion = assemblerVersion,
                    turnsDroppedByCap = turnsDropped,
                ),
            )

        val profile = config.profile(req.profileName)
        val redacted = RedactionChain.standard().redact(doc, profile)

        recordMetrics(redacted, scopeLabel, startNanos)
        return redacted
    }

    /**
     * The in-scope records, keyed by turn.
     *
     * **One query for a multi-turn scope.** `readForSession` exists precisely for
     * this — it orders by `seq` and excludes discarded turns in SQL — and reading
     * turn-by-turn instead would cost one round trip per turn on the surface a
     * user is waiting on (review-079 R7). A single-turn scope still goes by id:
     * fetching a whole session's rows to keep one of them would be the same
     * mistake pointed the other way.
     *
     * A store failure is not fatal here. Records missing from the map become
     * default instances downstream, the sections degrade, and the receipts say
     * `records` was short — the same shape as a scope whose turns were never
     * captured.
     */
    private fun readRecords(
        req: Request,
        inScope: List<TurnFacts>,
    ): Map<String, ProtocolRecord> {
        val wanted = inScope.map { it.turnId }.toSet()
        if (wanted.isEmpty()) return emptyMap()

        val rows =
            if (wanted.size == 1) {
                inScope.mapNotNull { runCatching { records.readByTurnId(UUID.fromString(it.turnId)) }.getOrNull() }
            } else {
                runCatching { records.readForSession(req.sessionId, lastN = null) }
                    .onFailure { log.warn("protocol: record read failed for session {}", req.sessionId, it) }
                    .getOrDefault(emptyList())
            }
        // Filter to the scope: `readForSession` answers for the whole session, and a
        // `lastN` document must not carry records for turns it does not narrate.
        return rows.filter { it.turnId in wanted }.associateBy { it.turnId }
    }

    /**
     * contracts §3.1 scope semantics; `lastN` takes the most recent N, oldest→newest.
     *
     * Then `caps.max-turns` bites (PT-10, review-080 R12). Every other cap in this
     * feature bounds a section's *content*; nothing bounded the number of turns, so
     * `scope=session` on a long-lived session built the whole thing in memory and
     * rendered it into one string. The newest turns are kept — a protocol is read
     * backwards from "what just happened" — and the shortfall is a receipt, never a
     * silent elision.
     */
    private fun selectTurns(req: Request): List<TurnFacts> {
        val scoped =
            when (req.scope.kindCase) {
                Scope.KindCase.LAST_TURN -> req.turns.takeLast(1)
                Scope.KindCase.LAST_N ->
                    req.turns.takeLast(
                        req.scope.lastN
                            .toInt()
                            .coerceAtLeast(0),
                    )
                else -> req.turns
            }
        return scoped
    }

    /**
     * All four sources concurrently, each in its own `async` with its own failure
     * isolation. Sequential fetching would make a protocol cost the SUM of four
     * network round trips on a debug surface a user is waiting on.
     *
     * **They are fetched ONCE, for one turn — the anchor (contracts A-9).** A
     * session-scope document spans many traces, and v1 does not fan out per turn.
     * The consequence is load-bearing and used to be silent: the returned
     * [ProtocolSources] describes the anchor turn and **nothing else**, so it is
     * stamped with [ProtocolSources.anchorTurnId] and every source-backed section on
     * every other turn degrades rather than rendering the anchor's facts as its own
     * (review-080 R1). Before that stamp existed, a 13-turn document printed turn 1's
     * dispatch target, worker, row count and duration under all 13 headings.
     */
    private suspend fun fetchSources(
        turns: List<TurnFacts>,
        recordsByTurn: Map<String, ProtocolRecord>,
        bearer: String,
    ): ProtocolSources =
        coroutineScope {
            // The anchor is the first in-scope turn that HAS a record — not simply the
            // first turn, which may never have been captured.
            val anchorTurn = turns.firstOrNull { recordsByTurn.containsKey(it.turnId) }
            val anchor = anchorTurn?.let { recordsByTurn[it.turnId] } ?: ProtocolRecord.getDefaultInstance()
            val p = anchor.pointers

            val gatewayJob =
                async {
                    gateway?.fetch(p.gatewayTurnRef, p.traceId, config.caps.llmCallRows, bearer)
                        ?: SourceOutcome.SkippedByConfig()
                }
            val lokiJob =
                async {
                    loki?.fetch(p.traceId, p.logWindowFrom, p.logWindowTo, config.caps.serviceLogsLines, bearer)
                        ?: SourceOutcome.SkippedByConfig()
                }
            val tempoJob = async { tempo?.fetch(p.traceId, bearer) ?: SourceOutcome.SkippedByConfig() }
            val explainJob =
                async {
                    // Only reconstruct when the turn carried no plan of its own (S-1).
                    // The reason travels with the skip: this is NOT a config decision, and
                    // reporting it as one told the reader their deployment was misconfigured
                    // when it was working correctly (review-080 R8).
                    if (p.planIdsCount > 0) {
                        SourceOutcome.SkippedByConfig("turn carried its own plan (S-1: not reconstructed)")
                    } else {
                        explain?.explainSql(p.sqlInline) ?: SourceOutcome.SkippedByConfig()
                    }
                }

            ProtocolSources(
                anchorTurnId = anchorTurn?.turnId.orEmpty(),
                gateway = gatewayJob.await().fold({ it }, { GatewaySource(status = it.first, detail = it.second) }),
                loki = lokiJob.await().fold({ it }, { LokiSource(status = it.first, detail = it.second) }),
                tempo = tempoJob.await().fold({ it }, { TempoSource(status = it.first, detail = it.second) }),
                explain = explain?.toSource(explainJob.await()) ?: ExplainSource(),
            )
        }

    private fun recordMetrics(
        doc: ProtocolDocument,
        scope: String,
        startNanos: Long,
    ) {
        val degraded =
            doc.turnsList.flatMap { it.sectionsList }.filter { it.status == SectionStatus.SECTION_DEGRADED }
        val outcome = if (degraded.isEmpty()) "ok" else "degraded"

        registry.counter("iris_protocol_generate_total", "scope", scope, "outcome", outcome).increment()
        registry
            .timer("iris_protocol_generate_duration_ms", "scope", scope)
            .record(java.time.Duration.ofNanos(System.nanoTime() - startNanos))
        degraded.forEach {
            registry.counter("iris_protocol_section_degraded_total", "key", it.key).increment()
        }
        if (degraded.isNotEmpty()) {
            log.info("protocol assembled with {} degraded section(s): {}", degraded.size, degraded.map { it.key })
        }
    }

    private companion object {
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        fun Scope.label(): String =
            when (kindCase) {
                Scope.KindCase.LAST_TURN -> "last"
                Scope.KindCase.WHOLE_SESSION -> "session"
                Scope.KindCase.LAST_N -> "lastN"
                else -> "unspecified"
            }

        /** Ok → payload; anything else → the (status, detail) an empty source should carry. */
        inline fun <T> SourceOutcome<T>.fold(
            onOk: (T) -> T,
            onOther: (Pair<SourceStatus, String>) -> T,
        ): T =
            when (this) {
                is SourceOutcome.Ok -> onOk(payload)
                is SourceOutcome.Degraded -> onOther(SourceStatus.DEGRADED to reason)
                is SourceOutcome.SkippedByConfig ->
                    onOther(SourceStatus.SKIPPED_BY_CONFIG to reason)
            }
    }
}
