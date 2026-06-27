package org.tatrman.kantheon.pythia.orchestrator

import org.tatrman.kantheon.pythia.v1.Status

/** Raised when an illegal `(from → to)` lifecycle transition is attempted. */
class IllegalTransition(
    val from: Status,
    val to: Status,
) : RuntimeException("illegal investigation transition $from → $to")

/**
 * The 12-status lifecycle transition table (design §3.4 + PD-11). The five
 * AWAITING_* pause states are reachable only from their owning active phase and
 * each resumes to a defined next status (and may HALT on TTL expiry / abandon).
 * Terminal statuses (DONE / FAILED / HALTED / INCONCLUSIVE) accept no outbound
 * transition.
 */
object TransitionTable {
    val TERMINALS: Set<Status> =
        setOf(Status.STATUS_DONE, Status.STATUS_FAILED, Status.STATUS_HALTED, Status.STATUS_INCONCLUSIVE)

    val AWAITING: Set<Status> =
        setOf(
            Status.STATUS_AWAITING_RESOLUTION_INPUT,
            Status.STATUS_AWAITING_PLAN_APPROVAL,
            Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL,
            Status.STATUS_AWAITING_USER_INPUT,
            Status.STATUS_AWAITING_BUDGET_DECISION,
        )

    /** Each AWAITING_* → the single control endpoint that resumes it (contracts §2). */
    val RESUME_ENDPOINT: Map<Status, String> =
        mapOf(
            Status.STATUS_AWAITING_RESOLUTION_INPUT to "/answer",
            Status.STATUS_AWAITING_PLAN_APPROVAL to "/approve-plan",
            Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL to "/approve-revision",
            Status.STATUS_AWAITING_USER_INPUT to "/answer",
            Status.STATUS_AWAITING_BUDGET_DECISION to "/budget-decision",
        )

    private val legal: Map<Status, Set<Status>> =
        mapOf(
            Status.STATUS_SUBMITTED to setOf(Status.STATUS_RESOLVING, Status.STATUS_FAILED),
            Status.STATUS_RESOLVING to
                setOf(
                    Status.STATUS_AWAITING_RESOLUTION_INPUT,
                    Status.STATUS_PLANNING,
                    Status.STATUS_FAILED,
                ),
            Status.STATUS_AWAITING_RESOLUTION_INPUT to
                setOf(Status.STATUS_RESOLVING, Status.STATUS_HALTED, Status.STATUS_FAILED),
            Status.STATUS_PLANNING to
                setOf(
                    Status.STATUS_AWAITING_PLAN_APPROVAL,
                    Status.STATUS_EXECUTING,
                    Status.STATUS_FAILED,
                ),
            Status.STATUS_AWAITING_PLAN_APPROVAL to
                setOf(Status.STATUS_EXECUTING, Status.STATUS_PLANNING, Status.STATUS_HALTED),
            Status.STATUS_EXECUTING to
                setOf(
                    Status.STATUS_AWAITING_USER_INPUT,
                    Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL,
                    Status.STATUS_AWAITING_BUDGET_DECISION,
                    Status.STATUS_SYNTHESIZING,
                    Status.STATUS_FAILED,
                    Status.STATUS_HALTED,
                    Status.STATUS_INCONCLUSIVE,
                ),
            Status.STATUS_AWAITING_USER_INPUT to
                setOf(Status.STATUS_EXECUTING, Status.STATUS_HALTED, Status.STATUS_FAILED),
            Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL to
                setOf(Status.STATUS_EXECUTING, Status.STATUS_HALTED),
            Status.STATUS_AWAITING_BUDGET_DECISION to
                setOf(Status.STATUS_EXECUTING, Status.STATUS_SYNTHESIZING, Status.STATUS_HALTED),
            Status.STATUS_SYNTHESIZING to
                setOf(Status.STATUS_DONE, Status.STATUS_HALTED, Status.STATUS_INCONCLUSIVE),
            // terminals — no outbound
            Status.STATUS_DONE to emptySet(),
            Status.STATUS_FAILED to emptySet(),
            Status.STATUS_HALTED to emptySet(),
            Status.STATUS_INCONCLUSIVE to emptySet(),
        )

    fun isLegal(
        from: Status,
        to: Status,
    ): Boolean = legal[from]?.contains(to) == true

    /** Validate `from → to`; throws [IllegalTransition] when not in the table. */
    fun validate(
        from: Status,
        to: Status,
    ) {
        if (!isLegal(from, to)) throw IllegalTransition(from, to)
    }

    fun isTerminal(status: Status): Boolean = status in TERMINALS

    fun isAwaiting(status: Status): Boolean = status in AWAITING
}
