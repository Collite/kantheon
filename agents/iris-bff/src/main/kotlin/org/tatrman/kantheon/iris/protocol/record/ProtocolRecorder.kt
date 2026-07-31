package org.tatrman.kantheon.iris.protocol.record

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.protocol.v1.CaptureGap
import org.tatrman.kantheon.protocol.v1.ProtocolHints
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import org.tatrman.kantheon.protocol.v1.RecordCaptures
import org.tatrman.kantheon.protocol.v1.RecordPointers
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger(ProtocolRecorder::class.java)

/** Everything the recorder needs about one finished turn. */
data class TurnRecordContext(
    val turnId: UUID,
    val startedAt: Instant,
    val completedAt: Instant,
    /**
     * The Themis response verbatim — capture F2. Serialized whole rather than
     * projected: the assembler's resolution section wants bindings, confidence,
     * layer hit and alternates, and re-deriving those from a projection later is
     * exactly the loss the record exists to prevent (PT-4/PT-5).
     */
    val resolveResponse: ResolveResponse?,
    /** The agent's block, stored verbatim and never edited (PT-25). */
    val hints: ProtocolHints?,
    val correlationId: String?,
)

/**
 * Writes one `iris_protocol_records` row per dispatched turn (architecture §1,
 * §7). Two rules govern it, and both are load-bearing:
 *
 * **Write-after-commit.** The caller invokes this only once the `iris_turns` row
 * is committed. `iris_protocol_records.turn_id` is a FK to that row, so an
 * earlier write would be rejected by Postgres — and the ordering also means a
 * record never outlives a turn that failed to persist.
 *
 * **Never fatal.** A protocol record is an observability artefact; the user's
 * answer does not depend on it. Every failure is swallowed, logged, and counted
 * on `iris_protocol_record_write_failures_total`. Rethrowing here would let a
 * broken debug surface take down live turns — the precise inversion of what this
 * feature is for.
 */
class ProtocolRecorder(
    private val store: ProtocolRecordStore,
    registry: MeterRegistry = SimpleMeterRegistry(),
) {
    private val failures = registry.counter("iris_protocol_record_write_failures_total")

    fun record(ctx: TurnRecordContext) {
        runCatching { store.write(buildRecord(ctx)) }
            .onFailure { e ->
                log.warn("protocol record write failed for turn {}", ctx.turnId, e)
                failures.increment()
            }
    }

    private fun buildRecord(ctx: TurnRecordContext): ProtocolRecord {
        val (from, to) = PointerSourcing.logWindow(ctx.startedAt, ctx.completedAt)
        val hints = ctx.hints ?: ProtocolHints.getDefaultInstance()

        val pointers =
            RecordPointers
                .newBuilder()
                .setTraceId(PointerSourcing.traceIdOrEmpty())
                .setCorrelationId(ctx.correlationId.orEmpty())
                // The BFF turn id IS the gateway turn ref: it is what golem forwards
                // as X-Turn-Ref, so the gateway's prompt_logs rows join on it.
                .setGatewayTurnRef(ctx.turnId.toString())
                .setLogWindowFrom(from)
                .setLogWindowTo(to)
                .setHints(hints)
                // Hoisted out of the hints block so the assembler reads one place.
                // The hints stay verbatim alongside — PT-25 — so a mismatch between
                // what the agent claimed and what we used stays visible.
                .addAllPlanIds(hints.planIdsList)
                .addAllLlmCallRefs(hints.llmCallRefsList)
                .setSqlRef(hints.sqlRef)
                .setSqlInline(hints.sqlInline)
                .addCaptureGaps(SECURITY_APPLIED_GAP)
                .build()

        val captures =
            RecordCaptures
                .newBuilder()
                .apply { ctx.resolveResponse?.let { resolveResponse = it.toByteString() } }
                // captures.security_applied stays unset — see SECURITY_APPLIED_GAP.
                .build()

        return ProtocolRecord
            .newBuilder()
            .setTurnId(ctx.turnId.toString())
            .setPointers(pointers)
            .setCaptures(captures)
            .setSchemaVersion(SchemaVersion.CURRENT)
            .build()
    }

    companion object {
        /**
         * Amendment A-1: F7 is structurally unreachable from kantheon today. The
         * authoritative `validate.v1 security_applied` set is consumed inside
         * ttr-query and carried on none of its response types, and the lossy
         * string proxy that does survive (`pipelineWarnings`) is dropped again by
         * golem's query client.
         *
         * The gap is recorded EXPLICITLY rather than left as an empty capture,
         * because empty is a legitimate answer — a query against tables with no
         * RLS rules applies none. Conflating "no rules" with "we could not look"
         * would make the assembler's security section quietly wrong instead of
         * honestly degraded (P-4, PT-13).
         *
         * Delete this the day the upstream wire carries the set; the capture is
         * then a one-line serialize (`validate.v1` is already on the classpath).
         */
        val SECURITY_APPLIED_GAP: CaptureGap =
            CaptureGap
                .newBuilder()
                .setCapture("security_applied")
                .setReason("ttr-query does not propagate validate.v1 security_applied to callers (A-1)")
                .build()
    }
}
