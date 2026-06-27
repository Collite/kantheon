package org.tatrman.kantheon.pythia.orchestrator

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.pythia.api.ProtoJson
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.events.Events
import org.tatrman.kantheon.pythia.persistence.InvestigationRecord
import org.tatrman.kantheon.pythia.persistence.InvestigationRepository
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.Status
import java.time.Clock

/**
 * Expires AWAITING_* parks past their TTL (default 24h, `pythia.awaiting.ttl-hours`):
 * sweeps investigations whose `awaiting_ttl_until < now`, transitions them to a
 * terminal HALTED (STOP_USER / TTL), and emits the lifecycle event + records a
 * Rule-6 "expired awaiting input" warning. The clock is injectable (no wall-clock waits).
 */
class TtlSweeper(
    private val investigations: InvestigationRepository,
    private val emitter: EventEmitter,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(TtlSweeper::class.java)

    /** Run one sweep; returns the number of investigations actually expired. */
    fun sweep(): Int {
        val expired = investigations.findExpiredAwaiting(clock.millis())
        var swept = 0
        for (rec in expired) {
            val from = Status.valueOf(rec.status)
            if (!TransitionTable.isLegal(from, Status.STATUS_HALTED)) continue
            // Status-conditional transition: if a concurrent resume already left the park,
            // the CAS loses and we skip — no clobbering the resumed status back to HALTED.
            if (!investigations.compareAndSetStatus(rec.id, from.name, Status.STATUS_HALTED.name)) continue
            investigations.save(
                rec.copy(
                    finalisedAt = clock.instant(),
                    warningsJson = appendWarning(rec.warningsJson, "expired awaiting input after the configured TTL"),
                ),
            )
            emitter.emit(rec.id, Events.statusChanged(from, Status.STATUS_HALTED, "ttl_expired"))
            emitter.emitLifecycle(rec.id, userIdOf(rec), from, Status.STATUS_HALTED)
            log.info("swept investigation {} (parked past TTL, {} → HALTED)", rec.id, from)
            swept++
        }
        return swept
    }

    private fun appendWarning(
        warningsJson: String,
        message: String,
    ): String {
        val existing =
            runCatching {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(warningsJson)
            }.getOrNull()
        val arr = (existing as? kotlinx.serialization.json.JsonArray)?.toMutableList() ?: mutableListOf()
        arr.add(kotlinx.serialization.json.JsonPrimitive(message))
        return kotlinx.serialization.json
            .JsonArray(arr)
            .toString()
    }

    private fun userIdOf(rec: InvestigationRecord): String =
        runCatching { ProtoJson.parseInto(rec.callerJson, Caller.newBuilder()).build().userId }.getOrDefault("")
}
