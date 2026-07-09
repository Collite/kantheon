package org.tatrman.kantheon.hebe.scheduler.offline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Missed-trigger catch-up per policy + coalescing (P2 Stage 2.5 T1/T2). Clock is
 * controlled; `next_run_at` is set in the past to simulate a down process.
 */
class CatchupSpec :
    StringSpec({

        val interval = 60L // seconds

        fun routine(
            policy: CatchupPolicy,
            nextRunAt: Long,
        ) = RoutineSchedule("r", nextRunAt, interval, policy)

        "run_once_on_wake fires exactly once regardless of how many ticks were missed" {
            // nextRunAt=100, now=1000 → 15 missed ticks, but only one owed fire.
            val plan =
                CatchupPlanner.plan(
                    listOf(routine(CatchupPolicy.RUN_ONCE_ON_WAKE, 100)),
                    now = 1000,
                    coalesce = false,
                )
            plan.fires shouldHaveSize 1
            plan.fires.single().routineId shouldBe "r"
            // next_run_at advanced strictly past now
            (plan.advancedNextRunAt["r"]!! > 1000) shouldBe true
        }

        "run_all_missed fires once per owed tick (incl. the one due exactly now)" {
            // nextRunAt=100, now=280, interval=60 → ticks 100,160,220,280 all owed (<= now)
            val plan =
                CatchupPlanner.plan(
                    listOf(routine(CatchupPolicy.RUN_ALL_MISSED, 100)),
                    now = 280,
                    coalesce = false,
                )
            plan.fires shouldHaveSize 4
            plan.fires.map { it.scheduledFor } shouldBe listOf(100L, 160L, 220L, 280L)
        }

        "run_all_missed with coalesce collapses the backlog to one fire" {
            val plan =
                CatchupPlanner.plan(
                    listOf(routine(CatchupPolicy.RUN_ALL_MISSED, 100)),
                    now = 10_000,
                    coalesce = true,
                )
            plan.fires shouldHaveSize 1
        }

        "skip produces no owed fire but advances next_run_at" {
            val plan =
                CatchupPlanner.plan(
                    listOf(routine(CatchupPolicy.SKIP, 100)),
                    now = 280,
                    coalesce = false,
                )
            plan.fires shouldHaveSize 0
            (plan.advancedNextRunAt["r"]!! > 280) shouldBe true
        }

        "a not-yet-due routine is untouched" {
            val plan =
                CatchupPlanner.plan(
                    listOf(routine(CatchupPolicy.RUN_ALL_MISSED, 5000)),
                    now = 1000,
                    coalesce = false,
                )
            plan.fires shouldHaveSize 0
            plan.advancedNextRunAt["r"] shouldBe 5000
        }

        "a routine due exactly now owes one tick" {
            val plan =
                CatchupPlanner.plan(
                    listOf(routine(CatchupPolicy.RUN_ALL_MISSED, 1000)),
                    now = 1000,
                    coalesce = false,
                )
            plan.fires shouldHaveSize 1
        }
    })
