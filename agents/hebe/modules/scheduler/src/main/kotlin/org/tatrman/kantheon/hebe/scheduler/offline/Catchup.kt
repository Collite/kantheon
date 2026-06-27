package org.tatrman.kantheon.hebe.scheduler.offline

/**
 * Missed-trigger catch-up (P2 Stage 2.5 T1/T2; architecture §7.1). When the
 * process was down across one or more due cron ticks, owed fires are detected on
 * boot and replayed per a per-routine [CatchupPolicy], with optional coalescing
 * so a long sleep doesn't produce a thundering herd. Only meaningful when
 * `platform.availability = intermittent` (idle on always-on profiles).
 *
 * Pure logic over a controlled clock — the durable `jobs` queue (standalone)
 * supplies the [RoutineSchedule]s; this planner decides what to fire and how to
 * advance `next_run_at`.
 */
enum class CatchupPolicy(val token: String) {
    /** One owed fire on boot regardless of how many were missed (default for kantheon_question). */
    RUN_ONCE_ON_WAKE("run_once_on_wake"),

    /** One fire per missed tick (subject to coalescing). */
    RUN_ALL_MISSED("run_all_missed"),

    /** No owed fire; `next_run_at` simply advances past now. */
    SKIP("skip"),
    ;

    companion object {
        fun from(token: String): CatchupPolicy =
            entries.firstOrNull { it.token == token } ?: RUN_ONCE_ON_WAKE
    }
}

data class RoutineSchedule(
    val id: String,
    val nextRunAt: Long,
    val intervalSeconds: Long,
    val policy: CatchupPolicy,
)

data class OwedFire(
    val routineId: String,
    val scheduledFor: Long,
)

data class CatchupPlan(
    val fires: List<OwedFire>,
    /** The new `next_run_at` per routine (always strictly after `now`). */
    val advancedNextRunAt: Map<String, Long>,
)

object CatchupPlanner {
    /**
     * Builds the owed-fire plan at [now]. [coalesce] collapses a
     * `run_all_missed` backlog into a single fire (the `[platform.catchup].coalesce`
     * config). Routines not yet due are untouched.
     */
    fun plan(
        routines: List<RoutineSchedule>,
        now: Long,
        coalesce: Boolean,
    ): CatchupPlan {
        val fires = mutableListOf<OwedFire>()
        val advanced = mutableMapOf<String, Long>()
        for (r in routines) {
            if (r.nextRunAt > now || r.intervalSeconds <= 0) {
                // Not due (or non-recurring): leave as-is.
                advanced[r.id] = r.nextRunAt
                continue
            }
            val missed = ((now - r.nextRunAt) / r.intervalSeconds) + 1
            advanced[r.id] = r.nextRunAt + missed * r.intervalSeconds
            when (r.policy) {
                CatchupPolicy.SKIP -> { /* no fire */ }
                CatchupPolicy.RUN_ONCE_ON_WAKE -> fires.add(OwedFire(r.id, r.nextRunAt))
                CatchupPolicy.RUN_ALL_MISSED ->
                    if (coalesce) {
                        fires.add(OwedFire(r.id, r.nextRunAt))
                    } else {
                        for (k in 0 until missed) {
                            fires.add(OwedFire(r.id, r.nextRunAt + k * r.intervalSeconds))
                        }
                    }
            }
        }
        return CatchupPlan(fires, advanced)
    }
}
