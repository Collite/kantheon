package org.tatrman.kantheon.golem.resolution.ladder

/**
 * RV-P5.2 — profile accounting: the three budgets that bound the RV-11 loop (contracts §3).
 *
 * Three, not one, and counted separately on purpose:
 *
 * * **LLM invocations** (`max_llm_invocations`) — what stops a ladder grinding. Lookup rounds
 *   are NOT counted (RV-33: `lookup` is deterministic), which is the whole reason the rung
 *   vocabulary distinguishes it.
 * * **Ladder wall clock** (`ladder_budget_ms`) — what bounds the deterministic rounds, which
 *   have no invocation count to bound them.
 * * **HITL rounds** (`hitl_rounds`) — ONE pool per conversation (RV-17), shared with any
 *   delegated plugin. The counter rides the snapshot, so a replayed resume cannot
 *   double-spend it. ⚑ In kantheon it can only be *per turn* until golem gets a conversation
 *   id (found at P5.1 T3, carried to P5.3 T4).
 *
 * ⛑ **The wall clock is anchored to the TURN, never to now**, and this is golem-py's P4
 * review bug carried across rather than rediscovered: every node built its own budget
 * object, so `elapsedMs()` measured the node and `ladder_budget_ms` could never expire
 * however long the turn ran. [turnStartedAtNanos] is passed in and is not defaulted for that
 * reason.
 *
 * The clock is injectable so a test can prove the wall-clock branch without sleeping — a
 * budget that can only be observed by waiting is a budget nobody tests.
 */
class Budgets(
    val profile: LadderProfile,
    private val turnStartedAtNanos: Long,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    fun elapsedMs(): Long = (clockNanos() - turnStartedAtNanos) / 1_000_000

    fun wallClockLeft(): Boolean = elapsedMs() < profile.ladderBudgetMs

    fun llmLeft(llmInvocations: Int): Boolean = llmInvocations < profile.maxLlmInvocations

    fun hitlLeft(hitlRounds: Int): Boolean = hitlRounds < profile.hitlRounds

    /**
     * A rung is runnable if the wall clock allows it and — for the LLM rungs only — the
     * invocation budget does.
     */
    fun canRun(
        rung: String,
        llmInvocations: Int,
    ): Boolean {
        if (!wallClockLeft()) return false
        if (rung in DETERMINISTIC_RUNGS) return true
        return llmLeft(llmInvocations)
    }

    fun runnable(
        rungs: List<String>,
        llmInvocations: Int,
    ): List<String> = rungs.filter { canRun(it, llmInvocations) }
}
